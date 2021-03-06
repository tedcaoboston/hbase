/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver;

import static org.apache.hadoop.hbase.HConstants.HFILE_BLOCK_CACHE_SIZE_KEY;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Chore;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.io.hfile.BlockCache;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.ResizableBlockCache;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.util.ReflectionUtils;

import com.google.common.annotations.VisibleForTesting;

/**
 * Manages tuning of Heap memory using <code>HeapMemoryTuner</code>.
 */
@InterfaceAudience.Private
public class HeapMemoryManager {
  private static final Log LOG = LogFactory.getLog(HeapMemoryManager.class);
  private static final int CONVERT_TO_PERCENTAGE = 100;
  private static final int CLUSTER_MINIMUM_MEMORY_THRESHOLD = 
    (int) (CONVERT_TO_PERCENTAGE * HConstants.HBASE_CLUSTER_MINIMUM_MEMORY_THRESHOLD);

  public static final String BLOCK_CACHE_SIZE_MAX_RANGE_KEY = "hfile.block.cache.size.max.range";
  public static final String BLOCK_CACHE_SIZE_MIN_RANGE_KEY = "hfile.block.cache.size.min.range";
  public static final String MEMSTORE_SIZE_MAX_RANGE_KEY = 
      "hbase.regionserver.global.memstore.size.max.range";
  public static final String MEMSTORE_SIZE_MIN_RANGE_KEY = 
      "hbase.regionserver.global.memstore.size.min.range";
  public static final String HBASE_RS_HEAP_MEMORY_TUNER_PERIOD = 
      "hbase.regionserver.heapmemory.tuner.period";
  public static final int HBASE_RS_HEAP_MEMORY_TUNER_DEFAULT_PERIOD = 5 * 60 * 1000;
  public static final String HBASE_RS_HEAP_MEMORY_TUNER_CLASS = 
      "hbase.regionserver.heapmemory.tuner.class";

  private float globalMemStorePercent;
  private float globalMemStorePercentMinRange;
  private float globalMemStorePercentMaxRange;

  private float blockCachePercent;
  private float blockCachePercentMinRange;
  private float blockCachePercentMaxRange;

  private final ResizableBlockCache blockCache;
  private final FlushRequester memStoreFlusher;
  private final Server server;

  private HeapMemoryTunerChore heapMemTunerChore = null;
  private final boolean tunerOn;

  private long maxHeapSize = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();

  public static HeapMemoryManager create(Configuration conf, FlushRequester memStoreFlusher,
      Server server) {
    BlockCache blockCache = CacheConfig.instantiateBlockCache(conf);
    if (blockCache instanceof ResizableBlockCache) {
      return new HeapMemoryManager((ResizableBlockCache) blockCache, memStoreFlusher, server);
    }
    return null;
  }

  @VisibleForTesting
  HeapMemoryManager(ResizableBlockCache blockCache, FlushRequester memStoreFlusher,
      Server server) {
    this.blockCache = blockCache;
    this.memStoreFlusher = memStoreFlusher;
    this.server = server;
    this.tunerOn = doInit(server.getConfiguration());
  }

  private boolean doInit(Configuration conf) {
    globalMemStorePercent = MemStoreFlusher.getGlobalMemStorePercent(conf);
    blockCachePercent = conf.getFloat(HFILE_BLOCK_CACHE_SIZE_KEY,
        HConstants.HFILE_BLOCK_CACHE_SIZE_DEFAULT);
    int gml = (int) (globalMemStorePercent * CONVERT_TO_PERCENTAGE);
    int bcul = (int) (blockCachePercent * CONVERT_TO_PERCENTAGE);
    if (CONVERT_TO_PERCENTAGE - (gml + bcul) < CLUSTER_MINIMUM_MEMORY_THRESHOLD) {
      throw new RuntimeException("Current heap configuration for MemStore and BlockCache exceeds "
          + "the threshold required for successful cluster operation. "
          + "The combined value cannot exceed 0.8. Please check " + "the settings for "
          + MemStoreFlusher.MEMSTORE_SIZE_KEY + " and " + HFILE_BLOCK_CACHE_SIZE_KEY
          + " in your configuration. " + MemStoreFlusher.MEMSTORE_SIZE_KEY + " is "
          + globalMemStorePercent + " and " + HFILE_BLOCK_CACHE_SIZE_KEY + " is "
          + blockCachePercent);
    }
    // Initialize max and min range for memstore heap space
    globalMemStorePercentMinRange = conf.getFloat(MEMSTORE_SIZE_MIN_RANGE_KEY,
        globalMemStorePercent);
    globalMemStorePercentMaxRange = conf.getFloat(MEMSTORE_SIZE_MAX_RANGE_KEY,
        globalMemStorePercent);
    if (globalMemStorePercent < globalMemStorePercentMinRange) {
      LOG.warn("Setting " + MEMSTORE_SIZE_MIN_RANGE_KEY + " to " + globalMemStorePercent
          + ", same value as " + MemStoreFlusher.MEMSTORE_SIZE_KEY
          + " because supplied value greater than initial memstore size value.");
      globalMemStorePercentMinRange = globalMemStorePercent;
      conf.setFloat(MEMSTORE_SIZE_MIN_RANGE_KEY, globalMemStorePercentMinRange);
    }
    if (globalMemStorePercent > globalMemStorePercentMaxRange) {
      LOG.warn("Setting " + MEMSTORE_SIZE_MAX_RANGE_KEY + " to " + globalMemStorePercent
          + ", same value as " + MemStoreFlusher.MEMSTORE_SIZE_KEY
          + " because supplied value less than initial memstore size value.");
      globalMemStorePercentMaxRange = globalMemStorePercent;
      conf.setFloat(MEMSTORE_SIZE_MAX_RANGE_KEY, globalMemStorePercentMaxRange);
    }
    if (globalMemStorePercent == globalMemStorePercentMinRange
        && globalMemStorePercent == globalMemStorePercentMaxRange) {
      return false;
    }
    // Initialize max and min range for block cache
    blockCachePercentMinRange = conf.getFloat(BLOCK_CACHE_SIZE_MIN_RANGE_KEY, blockCachePercent);
    blockCachePercentMaxRange = conf.getFloat(BLOCK_CACHE_SIZE_MAX_RANGE_KEY, blockCachePercent);
    if (blockCachePercent < blockCachePercentMinRange) {
      LOG.warn("Setting " + BLOCK_CACHE_SIZE_MIN_RANGE_KEY + " to " + blockCachePercent
          + ", same value as " + HFILE_BLOCK_CACHE_SIZE_KEY
          + " because supplied value greater than initial block cache size.");
      blockCachePercentMinRange = blockCachePercent;
      conf.setFloat(BLOCK_CACHE_SIZE_MIN_RANGE_KEY, blockCachePercentMinRange);
    }
    if (blockCachePercent > blockCachePercentMaxRange) {
      LOG.warn("Setting " + BLOCK_CACHE_SIZE_MAX_RANGE_KEY + " to " + blockCachePercent
          + ", same value as " + HFILE_BLOCK_CACHE_SIZE_KEY
          + " because supplied value less than initial block cache size.");
      blockCachePercentMaxRange = blockCachePercent;
      conf.setFloat(BLOCK_CACHE_SIZE_MAX_RANGE_KEY, blockCachePercentMaxRange);
    }
    if (blockCachePercent == blockCachePercentMinRange
        && blockCachePercent == blockCachePercentMaxRange) {
      return false;
    }

    gml = (int) (globalMemStorePercentMaxRange * CONVERT_TO_PERCENTAGE);
    bcul = (int) (blockCachePercentMinRange * CONVERT_TO_PERCENTAGE);
    if (CONVERT_TO_PERCENTAGE - (gml + bcul) < CLUSTER_MINIMUM_MEMORY_THRESHOLD) {
      throw new RuntimeException("Current heap configuration for MemStore and BlockCache exceeds "
          + "the threshold required for successful cluster operation. "
          + "The combined value cannot exceed 0.8. Please check the settings for "
          + MEMSTORE_SIZE_MAX_RANGE_KEY + " and " + BLOCK_CACHE_SIZE_MIN_RANGE_KEY
          + " in your configuration. " + MEMSTORE_SIZE_MAX_RANGE_KEY + " is "
          + globalMemStorePercentMaxRange + " and " + BLOCK_CACHE_SIZE_MIN_RANGE_KEY + " is "
          + blockCachePercentMinRange);
    }
    gml = (int) (globalMemStorePercentMinRange * CONVERT_TO_PERCENTAGE);
    bcul = (int) (blockCachePercentMaxRange * CONVERT_TO_PERCENTAGE);
    if (CONVERT_TO_PERCENTAGE - (gml + bcul) < CLUSTER_MINIMUM_MEMORY_THRESHOLD) {
      throw new RuntimeException("Current heap configuration for MemStore and BlockCache exceeds "
          + "the threshold required for successful cluster operation. "
          + "The combined value cannot exceed 0.8. Please check the settings for "
          + MEMSTORE_SIZE_MIN_RANGE_KEY + " and " + BLOCK_CACHE_SIZE_MAX_RANGE_KEY
          + " in your configuration. " + MEMSTORE_SIZE_MIN_RANGE_KEY + " is "
          + globalMemStorePercentMinRange + " and " + BLOCK_CACHE_SIZE_MAX_RANGE_KEY + " is "
          + blockCachePercentMaxRange);
    }
    return true;
  }

  public void start() {
    if (tunerOn) {
      LOG.info("Starting HeapMemoryTuner chore.");
      this.heapMemTunerChore = new HeapMemoryTunerChore();
      Threads.setDaemonThreadRunning(heapMemTunerChore.getThread());
      // Register HeapMemoryTuner as a memstore flush listener
      memStoreFlusher.registerFlushRequestListener(heapMemTunerChore);
    }
  }

  public void stop() {
    // The thread is Daemon. Just interrupting the ongoing process.
    if (tunerOn) {
      LOG.info("Stoping HeapMemoryTuner chore.");
      this.heapMemTunerChore.interrupt();
    }
  }

  // Used by the test cases.
  boolean isTunerOn() {
    return this.tunerOn;
  }

  private class HeapMemoryTunerChore extends Chore implements FlushRequestListener {
    private HeapMemoryTuner heapMemTuner;
    private AtomicLong blockedFlushCount = new AtomicLong();
    private AtomicLong unblockedFlushCount = new AtomicLong();
    private long evictCount = 0L;
    private TunerContext tunerContext = new TunerContext();

    public HeapMemoryTunerChore() {
      super(server.getServerName() + "-HeapMemoryTunerChore", server.getConfiguration().getInt(
          HBASE_RS_HEAP_MEMORY_TUNER_PERIOD, HBASE_RS_HEAP_MEMORY_TUNER_DEFAULT_PERIOD), server);
      Class<? extends HeapMemoryTuner> tunerKlass = server.getConfiguration().getClass(
          HBASE_RS_HEAP_MEMORY_TUNER_CLASS, DefaultHeapMemoryTuner.class, HeapMemoryTuner.class);
      heapMemTuner = ReflectionUtils.newInstance(tunerKlass, server.getConfiguration());
    }

    @Override
    protected void chore() {
      evictCount = blockCache.getStats().getEvictedCount() - evictCount;
      tunerContext.setBlockedFlushCount(blockedFlushCount.getAndSet(0));
      tunerContext.setUnblockedFlushCount(unblockedFlushCount.getAndSet(0));
      tunerContext.setEvictCount(evictCount);
      tunerContext.setCurBlockCacheSize(blockCachePercent);
      tunerContext.setCurMemStoreSize(globalMemStorePercent);
      TunerResult result = null;
      try {
        result = this.heapMemTuner.tune(tunerContext);
      } catch (Throwable t) {
        LOG.error("Exception thrown from the HeapMemoryTuner implementation", t);
      }
      if (result != null && result.needsTuning()) {
        float memstoreSize = result.getMemstoreSize();
        float blockCacheSize = result.getBlockCacheSize();
        LOG.debug("From HeapMemoryTuner new memstoreSize: " + memstoreSize
            + ". new blockCacheSize: " + blockCacheSize);
        if (memstoreSize < globalMemStorePercentMinRange) {
          LOG.info("New memstoreSize from HeapMemoryTuner " + memstoreSize + " is below min level "
              + globalMemStorePercentMinRange + ". Resetting memstoreSize to min size");
          memstoreSize = globalMemStorePercentMinRange;
        } else if (memstoreSize > globalMemStorePercentMaxRange) {
          LOG.info("New memstoreSize from HeapMemoryTuner " + memstoreSize + " is above max level "
              + globalMemStorePercentMaxRange + ". Resetting memstoreSize to max size");
          memstoreSize = globalMemStorePercentMaxRange;
        }
        if (blockCacheSize < blockCachePercentMinRange) {
          LOG.info("New blockCacheSize from HeapMemoryTuner " + blockCacheSize
              + " is below min level " + blockCachePercentMinRange
              + ". Resetting blockCacheSize to min size");
          blockCacheSize = blockCachePercentMinRange;
        } else if (blockCacheSize > blockCachePercentMaxRange) {
          LOG.info("New blockCacheSize from HeapMemoryTuner " + blockCacheSize
              + " is above max level " + blockCachePercentMaxRange
              + ". Resetting blockCacheSize to min size");
          blockCacheSize = blockCachePercentMaxRange;
        }
        int gml = (int) (memstoreSize * CONVERT_TO_PERCENTAGE);
        int bcul = (int) (blockCacheSize * CONVERT_TO_PERCENTAGE);
        if (CONVERT_TO_PERCENTAGE - (gml + bcul) < CLUSTER_MINIMUM_MEMORY_THRESHOLD) {
          LOG.info("Current heap configuration from HeapMemoryTuner exceeds "
              + "the threshold required for successful cluster operation. "
              + "The combined value cannot exceed 0.8. " + MemStoreFlusher.MEMSTORE_SIZE_KEY
              + " is " + memstoreSize + " and " + HFILE_BLOCK_CACHE_SIZE_KEY + " is "
              + blockCacheSize);
          // TODO can adjust the value so as not exceed 80%. Is that correct? may be.
        } else {
          long newBlockCacheSize = (long) (maxHeapSize * blockCacheSize);
          long newMemstoreSize = (long) (maxHeapSize * memstoreSize);
          LOG.info("Setting block cache heap size to " + newBlockCacheSize
              + " and memstore heap size to " + newMemstoreSize);
          blockCachePercent = blockCacheSize;
          blockCache.setMaxSize(newBlockCacheSize);
          globalMemStorePercent = memstoreSize;
          memStoreFlusher.setGlobalMemstoreLimit(newMemstoreSize);
        }
      }
    }

    @Override
    public void flushRequested(FlushType type, HRegion region) {
      switch (type) {
      case ABOVE_HIGHER_MARK:
        blockedFlushCount.incrementAndGet();
        break;
      case ABOVE_LOWER_MARK:
        unblockedFlushCount.incrementAndGet();
        break;
      default:
        // In case of normal flush don't do any action.
        break;
      }
    }
  }

  /**
   * POJO to pass all the relevant information required to do the heap memory tuning. It holds the
   * flush counts and block cache evictions happened within the interval. Also holds the current
   * heap percentage allocated for memstore and block cache.
   */
  public static final class TunerContext {
    private long blockedFlushCount;
    private long unblockedFlushCount;
    private long evictCount;
    private float curMemStoreSize;
    private float curBlockCacheSize;

    public long getBlockedFlushCount() {
      return blockedFlushCount;
    }

    public void setBlockedFlushCount(long blockedFlushCount) {
      this.blockedFlushCount = blockedFlushCount;
    }

    public long getUnblockedFlushCount() {
      return unblockedFlushCount;
    }

    public void setUnblockedFlushCount(long unblockedFlushCount) {
      this.unblockedFlushCount = unblockedFlushCount;
    }

    public long getEvictCount() {
      return evictCount;
    }

    public void setEvictCount(long evictCount) {
      this.evictCount = evictCount;
    }

    public float getCurMemStoreSize() {
      return curMemStoreSize;
    }

    public void setCurMemStoreSize(float curMemStoreSize) {
      this.curMemStoreSize = curMemStoreSize;
    }

    public float getCurBlockCacheSize() {
      return curBlockCacheSize;
    }

    public void setCurBlockCacheSize(float curBlockCacheSize) {
      this.curBlockCacheSize = curBlockCacheSize;
    }
  }

  /**
   * POJO which holds the result of memory tuning done by HeapMemoryTuner implementation.
   * It includes the new heap percentage for memstore and block cache.
   */
  public static final class TunerResult {
    private float memstoreSize;
    private float blockCacheSize;
    private final boolean needsTuning;

    public TunerResult(boolean needsTuning) {
      this.needsTuning = needsTuning;
    }

    public float getMemstoreSize() {
      return memstoreSize;
    }

    public void setMemstoreSize(float memstoreSize) {
      this.memstoreSize = memstoreSize;
    }

    public float getBlockCacheSize() {
      return blockCacheSize;
    }

    public void setBlockCacheSize(float blockCacheSize) {
      this.blockCacheSize = blockCacheSize;
    }

    public boolean needsTuning() {
      return needsTuning;
    }
  }
}
