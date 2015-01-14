/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.segment.incremental;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import com.metamx.common.ISE;
import io.druid.data.input.InputRow;
import io.druid.granularity.QueryGranularity;
import io.druid.query.aggregation.Aggregator;
import io.druid.query.aggregation.AggregatorFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 */
public class OnheapIncrementalIndex extends IncrementalIndex<Aggregator>
{
  private final ConcurrentNavigableMap<TimeAndDims, Integer> facts;
  private final List<Aggregator[]> aggList = Collections.synchronizedList(
      new ArrayList<Aggregator[]>()
      {
        @Override
        public Aggregator[] set(int pos, Aggregator[] val)
        {
          this.fillUpTo(pos);
          return super.set(pos, val);
        }

        @Override
        public Aggregator[] get(int pos)
        {
          fillUpTo(pos);
          return super.get(pos);
        }

        private void fillUpTo(int pos)
        {
          if (pos >= size()) {
            this.ensureCapacity(pos + 1);
            while (pos >= size()) {
              this.add(null);
            }
          }
        }
      }
  );

  private final int maxRowCount;

  private String outOfRowsReason = null;

  public OnheapIncrementalIndex(
      IncrementalIndexSchema incrementalIndexSchema,
      boolean deserializeComplexMetrics,
      int maxRowCount
  )
  {
    super(incrementalIndexSchema, deserializeComplexMetrics);
    this.facts = new ConcurrentSkipListMap<>();
    this.maxRowCount = maxRowCount;
  }

  public OnheapIncrementalIndex(
      long minTimestamp,
      QueryGranularity gran,
      final AggregatorFactory[] metrics,
      boolean deserializeComplexMetrics,
      int maxRowCount
  )
  {
    this(
        new IncrementalIndexSchema.Builder().withMinTimestamp(minTimestamp)
                                            .withQueryGranularity(gran)
                                            .withMetrics(metrics)
                                            .build(),
        deserializeComplexMetrics,
        maxRowCount
    );
  }

  public OnheapIncrementalIndex(
      long minTimestamp,
      QueryGranularity gran,
      final AggregatorFactory[] metrics,
      int maxRowCount
  )
  {
    this(
        new IncrementalIndexSchema.Builder().withMinTimestamp(minTimestamp)
                                            .withQueryGranularity(gran)
                                            .withMetrics(metrics)
                                            .build(),
        true,
        maxRowCount
    );
  }

  public OnheapIncrementalIndex(
      IncrementalIndexSchema incrementalIndexSchema,
      int maxRowCount
  )
  {
    this(incrementalIndexSchema, true, maxRowCount);
  }

  @Override
  public ConcurrentNavigableMap<TimeAndDims, Integer> getFacts()
  {
    return facts;
  }

  @Override
  protected DimDim makeDimDim(String dimension)
  {
    return new OnHeapDimDim();
  }

  @Override
  protected Aggregator[] initAggs(
      AggregatorFactory[] metrics, ThreadLocal<InputRow> in, boolean deserializeComplexMetrics
  )
  {
    return new Aggregator[metrics.length];
  }

  @Override
  protected Integer addToFacts(
      AggregatorFactory[] metrics,
      boolean deserializeComplexMetrics,
      InputRow row,
      AtomicInteger numEntries,
      TimeAndDims key,
      ThreadLocal<InputRow> in
  ) throws IndexSizeExceededException
  {
    Integer priorIdex = facts.get(key);
    final Boolean hadPriorValue = null != priorIdex;

    Aggregator[] aggs = hadPriorValue ? aggList.get(priorIdex) : new Aggregator[metrics.length];

    if (!hadPriorValue) {
      for (int i = 0; i < metrics.length; i++) {
        final AggregatorFactory agg = metrics[i];
        aggs[i] = agg.factorize(
            makeColumnSelectorFactory(agg, in, deserializeComplexMetrics)
        );
      }
    }
    synchronized (aggList) {
      // Synchronize across `aggList` and `facts`
      final Integer innerPriorIndex = facts.get(key);
      if (null != innerPriorIndex) {
        aggs = aggList.get(innerPriorIndex);
      } else {
        final Integer rowOffset = aggList.size();
        if (rowOffset >= maxRowCount && !facts.containsKey(key)) {
          throw new IndexSizeExceededException("Maximum number of rows reached");
        }
        aggList.add(aggs);
        if (aggList.size() != rowOffset + 1) {
          throw new ISE("Corrupt row offset");
        }

        // Last ditch sanity check
        final Integer prev = facts.putIfAbsent(key, rowOffset);
        if (null == prev) {
          numEntries.incrementAndGet();
        } else {
          // At this point we have an extra entry in aggList
          aggs = aggList.get(prev);
        }
      }
    }

    in.set(row);

    for (Aggregator agg : aggs) {
      synchronized (agg) {
        agg.aggregate();
      }
    }

    in.set(null);


    return numEntries.get();
  }

  @Override
  public boolean canAppendRow()
  {
    final boolean canAdd = size() < maxRowCount;
    if (!canAdd) {
      outOfRowsReason = String.format("Maximum number of rows [%d] reached", maxRowCount);
    }
    return canAdd;
  }

  @Override
  public String getOutOfRowsReason()
  {
    return outOfRowsReason;
  }

  @Override
  protected Aggregator[] getAggsForRow(int rowOffset)
  {
    return aggList.get(rowOffset);
  }

  @Override
  protected Object getAggVal(Aggregator agg, int rowOffset, int aggPosition)
  {
    return agg.get();
  }

  @Override
  public float getMetricFloatValue(int rowOffset, int aggOffset)
  {
    return aggList.get(rowOffset)[aggOffset].getFloat();
  }

  @Override
  public long getMetricLongValue(int rowOffset, int aggOffset)
  {
    return aggList.get(rowOffset)[aggOffset].getLong();
  }

  @Override
  public Object getMetricObjectValue(int rowOffset, int aggOffset)
  {
    return aggList.get(rowOffset)[aggOffset].get();
  }

  private static class OnHeapDimDim implements DimDim
  {
    private final Map<String, Integer> falseIds;
    private final Map<Integer, String> falseIdsReverse;
    private volatile String[] sortedVals = null;
    final ConcurrentMap<String, String> poorMansInterning = Maps.newConcurrentMap();

    public OnHeapDimDim()
    {
      BiMap<String, Integer> biMap = Maps.synchronizedBiMap(HashBiMap.<String, Integer>create());
      falseIds = biMap;
      falseIdsReverse = biMap.inverse();
    }

    /**
     * Returns the interned String value to allow fast comparisons using `==` instead of `.equals()`
     *
     * @see io.druid.segment.incremental.IncrementalIndexStorageAdapter.EntryHolderValueMatcherFactory#makeValueMatcher(String, String)
     */
    public String get(String str)
    {
      String prev = poorMansInterning.putIfAbsent(str, str);
      return prev != null ? prev : str;
    }

    public int getId(String value)
    {
      if (value == null) {
        value = "";
      }
      final Integer id = falseIds.get(value);
      return id == null ? -1 : id;
    }

    public String getValue(int id)
    {
      return falseIdsReverse.get(id);
    }

    public boolean contains(String value)
    {
      return falseIds.containsKey(value);
    }

    public int size()
    {
      return falseIds.size();
    }

    public synchronized int add(String value)
    {
      int id = falseIds.size();
      falseIds.put(value, id);
      return id;
    }

    public int getSortedId(String value)
    {
      assertSorted();
      return Arrays.binarySearch(sortedVals, value);
    }

    public String getSortedValue(int index)
    {
      assertSorted();
      return sortedVals[index];
    }

    public void sort()
    {
      if (sortedVals == null) {
        sortedVals = new String[falseIds.size()];

        int index = 0;
        for (String value : falseIds.keySet()) {
          sortedVals[index++] = value;
        }
        Arrays.sort(sortedVals);
      }
    }

    private void assertSorted()
    {
      if (sortedVals == null) {
        throw new ISE("Call sort() before calling the getSorted* methods.");
      }
    }

    public boolean compareCannonicalValues(String s1, String s2)
    {
      return s1 == s2;
    }
  }
}
