/*
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
package org.apache.beam.runners.flink.translation.wrappers.streaming.io;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.apache.beam.runners.flink.FlinkPipelineOptions;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.ValueWithRecordId;
import org.apache.beam.vendor.guava.v20_0.com.google.common.base.Joiner;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.operators.StreamSource;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatus;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatusMaintainer;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;
import org.apache.flink.streaming.util.AbstractStreamOperatorTestHarness;
import org.apache.flink.util.InstantiationUtil;
import org.apache.flink.util.OutputTag;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tests for {@link UnboundedSourceWrapper}. */
@RunWith(Enclosed.class)
public class UnboundedSourceWrapperTest {

  private static final Logger LOG = LoggerFactory.getLogger(UnboundedSourceWrapperTest.class);

  /** Parameterized tests. */
  @RunWith(Parameterized.class)
  public static class ParameterizedUnboundedSourceWrapperTest {
    private final int numTasks;
    private final int numSplits;

    public ParameterizedUnboundedSourceWrapperTest(int numTasks, int numSplits) {
      this.numTasks = numTasks;
      this.numSplits = numSplits;
    }

    @Parameterized.Parameters(name = "numTasks = {0}; numSplits={1}")
    public static Collection<Object[]> data() {
      /*
       * Parameters for initializing the tests:
       * {numTasks, numSplits}
       * The test currently assumes powers of two for some assertions.
       */
      return Arrays.asList(
          new Object[][] {
            {1, 1}, {1, 2}, {1, 4},
            {2, 1}, {2, 2}, {2, 4},
            {4, 1}, {4, 2}, {4, 4}
          });
    }

    /**
     * Creates a {@link UnboundedSourceWrapper} that has one or multiple readers per source. If
     * numSplits > numTasks the source has one source will manage multiple readers.
     */
    @Test(timeout = 30_000)
    public void testValueEmission() throws Exception {
      final int numElementsPerShard = 20;
      FlinkPipelineOptions options = PipelineOptionsFactory.as(FlinkPipelineOptions.class);
      options.setShutdownSourcesOnFinalWatermark(true);

      final long[] numElementsReceived = {0L};
      final int[] numWatermarksReceived = {0};

      // this source will emit exactly NUM_ELEMENTS for each parallel reader,
      // afterwards it will stall. We check whether we also receive NUM_ELEMENTS
      // elements later.
      TestCountingSource source =
          new TestCountingSource(numElementsPerShard).withFixedNumSplits(numSplits);

      for (int subtaskIndex = 0; subtaskIndex < numTasks; subtaskIndex++) {
        UnboundedSourceWrapper<KV<Integer, Integer>, TestCountingSource.CounterMark> flinkWrapper =
            new UnboundedSourceWrapper<>("stepName", options, source, numTasks);

        // the source wrapper will only request as many splits as there are tasks and the source
        // will create at most numSplits splits
        assertEquals(numSplits, flinkWrapper.getSplitSources().size());

        StreamSource<
                WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>,
                UnboundedSourceWrapper<KV<Integer, Integer>, TestCountingSource.CounterMark>>
            sourceOperator = new StreamSource<>(flinkWrapper);

        AbstractStreamOperatorTestHarness<WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>>
            testHarness =
                new AbstractStreamOperatorTestHarness<>(
                    sourceOperator,
                    numTasks /* max parallelism */,
                    numTasks /* parallelism */,
                    subtaskIndex /* subtask index */);

        // The testing timer service is synchronous, so we must configure a watermark interval
        // > 0, otherwise we can get loop infinitely due to a timer always becoming ready after
        // it has been set.
        testHarness.getExecutionConfig().setAutoWatermarkInterval(10L);
        testHarness.setProcessingTime(System.currentTimeMillis());
        testHarness.setTimeCharacteristic(TimeCharacteristic.EventTime);

        // start a thread that advances processing time, so that we eventually get the final
        // watermark which is only updated via a processing-time trigger
        Thread processingTimeUpdateThread =
            new Thread() {
              @Override
              public void run() {
                while (true) {
                  try {
                    // Need to advance this so that the watermark timers in the source wrapper fire
                    // Synchronize is necessary because this can interfere with updating the
                    // PriorityQueue of the ProcessingTimeService which is accessed when setting
                    // timers in UnboundedSourceWrapper.
                    synchronized (testHarness.getCheckpointLock()) {
                      testHarness.setProcessingTime(System.currentTimeMillis());
                    }
                    Thread.sleep(100);
                  } catch (InterruptedException e) {
                    // this is ok
                    break;
                  } catch (Exception e) {
                    LOG.error("Unexpected error advancing processing time", e);
                    break;
                  }
                }
              }
            };

        processingTimeUpdateThread.start();

        try {
          testHarness.open();
          sourceOperator.run(
              testHarness.getCheckpointLock(),
              new TestStreamStatusMaintainer(),
              new Output<StreamRecord<WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>>>() {
                private boolean hasSeenMaxWatermark = false;

                @Override
                public void emitWatermark(Watermark watermark) {
                  // we get this when there is no more data
                  // it can happen that we get the max watermark several times, so guard against
                  // this
                  if (!hasSeenMaxWatermark
                      && watermark.getTimestamp()
                          >= BoundedWindow.TIMESTAMP_MAX_VALUE.getMillis()) {
                    numWatermarksReceived[0]++;
                    hasSeenMaxWatermark = true;
                  }
                }

                @Override
                public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> streamRecord) {
                  collect((StreamRecord) streamRecord);
                }

                @Override
                public void emitLatencyMarker(LatencyMarker latencyMarker) {}

                @Override
                public void collect(
                    StreamRecord<WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>>
                        windowedValueStreamRecord) {
                  numElementsReceived[0]++;
                }

                @Override
                public void close() {}
              });
        } finally {
          processingTimeUpdateThread.interrupt();
          processingTimeUpdateThread.join();
        }
      }
      // verify that we get the expected count across all subtasks
      assertEquals(numElementsPerShard * numSplits, numElementsReceived[0]);
      // and that we get as many final watermarks as there are subtasks
      assertEquals(numTasks, numWatermarksReceived[0]);
    }

    /**
     * Creates a {@link UnboundedSourceWrapper} that has one or multiple readers per source. If
     * numSplits > numTasks the source will manage multiple readers.
     *
     * <p>This test verifies that watermarks are correctly forwarded.
     */
    @Test(timeout = 30_000)
    public void testWatermarkEmission() throws Exception {
      final int numElements = 500;
      PipelineOptions options = PipelineOptionsFactory.create();

      // this source will emit exactly NUM_ELEMENTS across all parallel readers,
      // afterwards it will stall. We check whether we also receive NUM_ELEMENTS
      // elements later.
      TestCountingSource source = new TestCountingSource(numElements);
      UnboundedSourceWrapper<KV<Integer, Integer>, TestCountingSource.CounterMark> flinkWrapper =
          new UnboundedSourceWrapper<>("stepName", options, source, numSplits);

      assertEquals(numSplits, flinkWrapper.getSplitSources().size());

      final StreamSource<
              WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>,
              UnboundedSourceWrapper<KV<Integer, Integer>, TestCountingSource.CounterMark>>
          sourceOperator = new StreamSource<>(flinkWrapper);

      final AbstractStreamOperatorTestHarness<
              WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>>
          testHarness =
              new AbstractStreamOperatorTestHarness<>(
                  sourceOperator,
                  numTasks /* max parallelism */,
                  numTasks /* parallelism */,
                  0 /* subtask index */);
      testHarness.getExecutionConfig().setLatencyTrackingInterval(0);
      testHarness.getExecutionConfig().setAutoWatermarkInterval(1);

      testHarness.setProcessingTime(Long.MIN_VALUE);
      testHarness.setTimeCharacteristic(TimeCharacteristic.EventTime);

      final ConcurrentLinkedQueue<Object> caughtExceptions = new ConcurrentLinkedQueue<>();

      // We test emission of two watermarks here, one intermediate, one final
      final CountDownLatch seenWatermarks = new CountDownLatch(2);
      final int minElementsPerReader = numElements / numSplits;
      final CountDownLatch minElementsCountdown = new CountDownLatch(minElementsPerReader);

      // first halt the source to test auto watermark emission
      source.haltEmission();
      testHarness.open();

      Thread sourceThread =
          new Thread(
              () -> {
                try {
                  sourceOperator.run(
                      testHarness.getCheckpointLock(),
                      new TestStreamStatusMaintainer(),
                      new Output<
                          StreamRecord<WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>>>() {

                        @Override
                        public void emitWatermark(Watermark watermark) {
                          seenWatermarks.countDown();
                        }

                        @Override
                        public <X> void collect(
                            OutputTag<X> outputTag, StreamRecord<X> streamRecord) {}

                        @Override
                        public void emitLatencyMarker(LatencyMarker latencyMarker) {}

                        @Override
                        public void collect(
                            StreamRecord<WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>>
                                windowedValueStreamRecord) {
                          minElementsCountdown.countDown();
                        }

                        @Override
                        public void close() {}
                      });
                } catch (Exception e) {
                  LOG.info("Caught exception:", e);
                  caughtExceptions.add(e);
                }
              });

      sourceThread.start();

      while (flinkWrapper.getLocalReaders().stream()
          .anyMatch(reader -> reader.getWatermark().getMillis() == 0)) {
        // readers haven't been initialized
        Thread.sleep(50);
      }

      // Need to advance this so that the watermark timers in the source wrapper fire
      // Synchronize is necessary because this can interfere with updating the PriorityQueue
      // of the ProcessingTimeService which is also accessed through UnboundedSourceWrapper.
      synchronized (testHarness.getCheckpointLock()) {
        testHarness.setProcessingTime(0);
      }

      // now read the elements
      source.continueEmission();
      // ..and await elements
      minElementsCountdown.await();

      // Need to advance this so that the watermark timers in the source wrapper fire
      // Synchronize is necessary because this can interfere with updating the PriorityQueue
      // of the ProcessingTimeService which is also accessed through UnboundedSourceWrapper.
      synchronized (testHarness.getCheckpointLock()) {
        testHarness.setProcessingTime(Long.MAX_VALUE);
      }

      seenWatermarks.await();

      if (!caughtExceptions.isEmpty()) {
        fail("Caught exception(s): " + Joiner.on(",").join(caughtExceptions));
      }

      sourceOperator.cancel();
      sourceThread.join();
    }

    /**
     * Verify that snapshot/restore work as expected. We bring up a source and cancel after seeing a
     * certain number of elements. Then we snapshot that source, bring up a completely new source
     * that we restore from the snapshot and verify that we see all expected elements in the end.
     */
    @Test
    public void testRestore() throws Exception {
      final int numElements = 20;
      final Object checkpointLock = new Object();
      PipelineOptions options = PipelineOptionsFactory.create();

      // this source will emit exactly NUM_ELEMENTS across all parallel readers,
      // afterwards it will stall. We check whether we also receive NUM_ELEMENTS
      // elements later.
      TestCountingSource source = new TestCountingSource(numElements);
      UnboundedSourceWrapper<KV<Integer, Integer>, TestCountingSource.CounterMark> flinkWrapper =
          new UnboundedSourceWrapper<>("stepName", options, source, numSplits);

      assertEquals(numSplits, flinkWrapper.getSplitSources().size());

      StreamSource<
              WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>,
              UnboundedSourceWrapper<KV<Integer, Integer>, TestCountingSource.CounterMark>>
          sourceOperator = new StreamSource<>(flinkWrapper);

      AbstractStreamOperatorTestHarness<WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>>
          testHarness =
              new AbstractStreamOperatorTestHarness<>(
                  sourceOperator,
                  numTasks /* max parallelism */,
                  numTasks /* parallelism */,
                  0 /* subtask index */);

      testHarness.setTimeCharacteristic(TimeCharacteristic.EventTime);

      final Set<KV<Integer, Integer>> emittedElements = new HashSet<>();

      boolean readFirstBatchOfElements = false;

      try {
        testHarness.open();
        sourceOperator.run(
            checkpointLock,
            new TestStreamStatusMaintainer(),
            new Output<StreamRecord<WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>>>() {
              private int count = 0;

              @Override
              public void emitWatermark(Watermark watermark) {}

              @Override
              public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> streamRecord) {
                collect((StreamRecord) streamRecord);
              }

              @Override
              public void emitLatencyMarker(LatencyMarker latencyMarker) {}

              @Override
              public void collect(
                  StreamRecord<WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>>
                      windowedValueStreamRecord) {

                emittedElements.add(windowedValueStreamRecord.getValue().getValue().getValue());
                count++;
                if (count >= numElements / 2) {
                  throw new SuccessException();
                }
              }

              @Override
              public void close() {}
            });
      } catch (SuccessException e) {
        // success
        readFirstBatchOfElements = true;
      }

      assertTrue("Did not successfully read first batch of elements.", readFirstBatchOfElements);

      // draw a snapshot
      OperatorSubtaskState snapshot = testHarness.snapshot(0, 0);

      // test that finalizeCheckpoint on CheckpointMark is called
      final ArrayList<Integer> finalizeList = new ArrayList<>();
      TestCountingSource.setFinalizeTracker(finalizeList);
      testHarness.notifyOfCompletedCheckpoint(0);
      assertEquals(flinkWrapper.getLocalSplitSources().size(), finalizeList.size());

      // create a completely new source but restore from the snapshot
      TestCountingSource restoredSource = new TestCountingSource(numElements);
      UnboundedSourceWrapper<KV<Integer, Integer>, TestCountingSource.CounterMark>
          restoredFlinkWrapper =
              new UnboundedSourceWrapper<>("stepName", options, restoredSource, numSplits);

      assertEquals(numSplits, restoredFlinkWrapper.getSplitSources().size());

      StreamSource<
              WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>,
              UnboundedSourceWrapper<KV<Integer, Integer>, TestCountingSource.CounterMark>>
          restoredSourceOperator = new StreamSource<>(restoredFlinkWrapper);

      // set parallelism to 1 to ensure that our testing operator gets all checkpointed state
      AbstractStreamOperatorTestHarness<WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>>
          restoredTestHarness =
              new AbstractStreamOperatorTestHarness<>(
                  restoredSourceOperator,
                  numTasks /* max parallelism */,
                  1 /* parallelism */,
                  0 /* subtask index */);

      restoredTestHarness.setTimeCharacteristic(TimeCharacteristic.EventTime);

      // restore snapshot
      restoredTestHarness.initializeState(snapshot);

      boolean readSecondBatchOfElements = false;

      // run again and verify that we see the other elements
      try {
        restoredTestHarness.open();
        restoredSourceOperator.run(
            checkpointLock,
            new TestStreamStatusMaintainer(),
            new Output<StreamRecord<WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>>>() {
              private int count = 0;

              @Override
              public void emitWatermark(Watermark watermark) {}

              @Override
              public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> streamRecord) {
                collect((StreamRecord) streamRecord);
              }

              @Override
              public void emitLatencyMarker(LatencyMarker latencyMarker) {}

              @Override
              public void collect(
                  StreamRecord<WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>>
                      windowedValueStreamRecord) {
                emittedElements.add(windowedValueStreamRecord.getValue().getValue().getValue());
                count++;
                if (count >= numElements / 2) {
                  throw new SuccessException();
                }
              }

              @Override
              public void close() {}
            });
      } catch (SuccessException e) {
        // success
        readSecondBatchOfElements = true;
      }

      assertEquals(
          Math.max(1, numSplits / numTasks), restoredFlinkWrapper.getLocalSplitSources().size());

      assertTrue("Did not successfully read second batch of elements.", readSecondBatchOfElements);

      // verify that we saw all NUM_ELEMENTS elements
      assertTrue(emittedElements.size() == numElements);
    }

    @Test
    public void testNullCheckpoint() throws Exception {
      final int numElements = 20;
      PipelineOptions options = PipelineOptionsFactory.create();

      TestCountingSource source =
          new TestCountingSource(numElements) {
            @Override
            public Coder<CounterMark> getCheckpointMarkCoder() {
              return null;
            }
          };

      UnboundedSourceWrapper<KV<Integer, Integer>, TestCountingSource.CounterMark> flinkWrapper =
          new UnboundedSourceWrapper<>("stepName", options, source, numSplits);

      StreamSource<
              WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>,
              UnboundedSourceWrapper<KV<Integer, Integer>, TestCountingSource.CounterMark>>
          sourceOperator = new StreamSource<>(flinkWrapper);

      AbstractStreamOperatorTestHarness<WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>>
          testHarness =
              new AbstractStreamOperatorTestHarness<>(
                  sourceOperator,
                  numTasks /* max parallelism */,
                  numTasks /* parallelism */,
                  0 /* subtask index */);

      testHarness.setTimeCharacteristic(TimeCharacteristic.EventTime);

      testHarness.open();

      OperatorSubtaskState snapshot = testHarness.snapshot(0, 0);

      UnboundedSourceWrapper<KV<Integer, Integer>, TestCountingSource.CounterMark>
          restoredFlinkWrapper =
              new UnboundedSourceWrapper<>(
                  "stepName", options, new TestCountingSource(numElements), numSplits);

      StreamSource<
              WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>,
              UnboundedSourceWrapper<KV<Integer, Integer>, TestCountingSource.CounterMark>>
          restoredSourceOperator = new StreamSource<>(restoredFlinkWrapper);

      // set parallelism to 1 to ensure that our testing operator gets all checkpointed state
      AbstractStreamOperatorTestHarness<WindowedValue<ValueWithRecordId<KV<Integer, Integer>>>>
          restoredTestHarness =
              new AbstractStreamOperatorTestHarness<>(
                  restoredSourceOperator,
                  numTasks /* max parallelism */,
                  1 /* parallelism */,
                  0 /* subtask index */);

      restoredTestHarness.setup();
      restoredTestHarness.initializeState(snapshot);
      restoredTestHarness.open();

      // when the source checkpointed a null we don't re-initialize the splits, that is we
      // will have no splits.
      assertEquals(0, restoredFlinkWrapper.getLocalSplitSources().size());
    }

    /** A special {@link RuntimeException} that we throw to signal that the test was successful. */
    private static class SuccessException extends RuntimeException {}
  }

  /** Not parameterized tests. */
  @RunWith(JUnit4.class)
  public static class BasicTest {

    /** Check serialization a {@link UnboundedSourceWrapper}. */
    @Test
    public void testSerialization() throws Exception {
      final int parallelism = 1;
      final int numElements = 20;
      PipelineOptions options = PipelineOptionsFactory.create();

      TestCountingSource source = new TestCountingSource(numElements);
      UnboundedSourceWrapper<KV<Integer, Integer>, TestCountingSource.CounterMark> flinkWrapper =
          new UnboundedSourceWrapper<>("stepName", options, source, parallelism);

      InstantiationUtil.serializeObject(flinkWrapper);
    }

    @Test(timeout = 10_000)
    public void testSourceWithNoReaderDoesNotShutdown() throws Exception {
      testSourceDoesNotShutdown(false);
    }

    @Test(timeout = 10_000)
    public void testSourceWithReadersDoesNotShutdown() throws Exception {
      testSourceDoesNotShutdown(true);
    }

    private static void testSourceDoesNotShutdown(boolean shouldHaveReaders) throws Exception {
      final int parallelism = 2;
      FlinkPipelineOptions options = PipelineOptionsFactory.as(FlinkPipelineOptions.class);

      TestCountingSource source = new TestCountingSource(20).withoutSplitting();

      UnboundedSourceWrapper<KV<Integer, Integer>, TestCountingSource.CounterMark> sourceWrapper =
          new UnboundedSourceWrapper<>("noReader", options, source, parallelism);

      StreamingRuntimeContext mock = Mockito.mock(StreamingRuntimeContext.class);
      if (shouldHaveReaders) {
        // Since the source can't be split, the first subtask index will read everything
        Mockito.when(mock.getIndexOfThisSubtask()).thenReturn(0);
      } else {
        // Set up the RuntimeContext such that this instance won't receive any readers
        Mockito.when(mock.getIndexOfThisSubtask()).thenReturn(parallelism - 1);
      }

      Mockito.when(mock.getNumberOfParallelSubtasks()).thenReturn(parallelism);
      Mockito.when(mock.getExecutionConfig()).thenReturn(new ExecutionConfig());
      ProcessingTimeService timerService = Mockito.mock(ProcessingTimeService.class);
      Mockito.when(timerService.getCurrentProcessingTime()).thenReturn(Long.MAX_VALUE);
      Mockito.when(mock.getProcessingTimeService()).thenReturn(timerService);

      sourceWrapper.setRuntimeContext(mock);
      sourceWrapper.open(new Configuration());

      SourceFunction.SourceContext sourceContext = Mockito.mock(SourceFunction.SourceContext.class);
      Object checkpointLock = new Object();
      Mockito.when(sourceContext.getCheckpointLock()).thenReturn(checkpointLock);
      // Initialize source context early to avoid concurrency issues with its initialization in the
      // run
      // method and the onProcessingTime call on the wrapper.
      sourceWrapper.setSourceContext(sourceContext);

      sourceWrapper.open(new Configuration());
      assertThat(sourceWrapper.getLocalReaders().isEmpty(), is(!shouldHaveReaders));

      Thread thread =
          new Thread(
              () -> {
                try {
                  sourceWrapper.run(sourceContext);
                } catch (Exception e) {
                  LOG.error("Error while running UnboundedSourceWrapper", e);
                }
              });

      try {
        thread.start();
        // Wait to see if the wrapper shuts down immediately in case it doesn't have readers
        if (!shouldHaveReaders) {
          // The expected state is for finalizeSource to sleep instead of exiting
          while (true) {
            StackTraceElement[] callStack = thread.getStackTrace();
            if (callStack.length >= 2
                && "sleep".equals(callStack[0].getMethodName())
                && "finalizeSource".equals(callStack[1].getMethodName())) {
              break;
            }
            Thread.sleep(10);
          }
        }
        // Source should still be running even if there are no readers
        assertThat(sourceWrapper.isRunning(), is(true));
        synchronized (checkpointLock) {
          // Trigger emission of the watermark by updating processing time.
          // The actual processing time value does not matter.
          sourceWrapper.onProcessingTime(42);
        }
        // Source should still be running even when watermark is at max
        assertThat(sourceWrapper.isRunning(), is(true));
        assertThat(thread.isAlive(), is(true));
        sourceWrapper.cancel();
      } finally {
        thread.interrupt();
        // try to join but also don't mask exceptions with test timeout
        thread.join(1000);
      }
      assertThat(thread.isAlive(), is(false));
    }
  }

  private static final class TestStreamStatusMaintainer implements StreamStatusMaintainer {
    StreamStatus currentStreamStatus = StreamStatus.ACTIVE;

    @Override
    public void toggleStreamStatus(StreamStatus streamStatus) {
      if (!currentStreamStatus.equals(streamStatus)) {
        currentStreamStatus = streamStatus;
      }
    }

    @Override
    public StreamStatus getStreamStatus() {
      return currentStreamStatus;
    }
  }
}
