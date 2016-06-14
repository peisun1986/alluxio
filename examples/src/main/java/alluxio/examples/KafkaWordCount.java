/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.examples;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function0;
import org.apache.spark.api.java.function.Function3;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.State;
import org.apache.spark.streaming.StateSpec;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaMapWithStateDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaPairReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka.KafkaUtils;
import scala.Tuple2;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Consumes messages from one or more topics in Kafka and does wordcount.
 *
 * Usage: JavaKafkaWordCount <zkQuorum> <group> <topics> <numThreads> <batchSize> <outputPath>
 *   <checkpointDirectory>
 *   <zkQuorum> is a list of one or more zookeeper servers that make quorum
 *   <group> is the name of kafka consumer group
 *   <topics> is a list of one or more kafka topics to consume from
 *   <numThreads> is the number of threads the kafka consumer should use
 *   <batchSize> is the spark streaming window in seconds
 *   <outputPath> is the output file name to store the result
 *   <checkpointDirectory> is the directory to save the streaming checkpoint which can be set to
 *     an alluxio directory
 *
 * To run this example:
 *    First create a kafka topic and publish some messages to it. Then run this command:
 *    /pathToSpark/bin/spark-submit --name "AlluxioSparkStreamingTest" --master local[2] \
 *    --class alluxio.examples.KafkaWordCount \
 *    /alluxioRoot/examples/target/alluxio-examples-${ALLUXIO_VERSION}-jar-with-dependencies.jar \
 *    zoo01,zoo02 my-consumer-group testTopic1,testTopic2 1 2 /tmp/output
 *    "alluxio://localhost:19998/checkpoint"
 */

public final class KafkaWordCount {
  private static final Pattern SPACE = Pattern.compile(" ");

  private KafkaWordCount() {
  }

  private static JavaStreamingContext createContext(String zkQuorum, String group, String topics,
      int numThreads, int batchSize, String outputPath, String checkpointDirectory) {
    System.out.println("Creating new context.");

    final File outputFile = new File(outputPath);
    if (outputFile.exists()) {
      outputFile.delete();
    }

    SparkConf sparkConf = new SparkConf().setAppName("KafkaWordCount");
    JavaStreamingContext ssc = new JavaStreamingContext(sparkConf, Durations.seconds(batchSize));
    ssc.checkpoint(checkpointDirectory);

    Map<String, Integer> topicMap = new HashMap<>();
    for (String topic : topics.split(",")) {
      topicMap.put(topic, numThreads);
    }

    JavaPairReceiverInputDStream<String, String> messages =
        KafkaUtils.createStream(ssc, zkQuorum, group, topicMap);

    JavaDStream<String> lines = messages.map(new Function<Tuple2<String, String>, String>() {
      @Override
      public String call(Tuple2<String, String> tuple2) {
        return tuple2._2();
      }
    });

    JavaDStream<String> words = lines.flatMap(new FlatMapFunction<String, String>() {
      @Override
      public Iterator<String> call(String x) {
        return Arrays.asList(SPACE.split(x)).iterator();
      }
    });


    JavaPairDStream<String, Integer> wordsDstream =
        words.mapToPair(new PairFunction<String, String, Integer>() {
          @Override
          public Tuple2<String, Integer> call(String s) {
            return new Tuple2<String, Integer>(s, 1);
          }
        });

    // Initial state RDD input to mapWithState
    @SuppressWarnings("unchecked") List<Tuple2<String, Integer>> tuples =
        Arrays.asList(new Tuple2<>("hello", 1), new Tuple2<>("world", 1));
    JavaPairRDD<String, Integer> initialRDD = ssc.sparkContext().parallelizePairs(tuples);


    // Update the cumulative count function
    Function3<String, org.apache.spark.api.java.Optional<Integer>, State<Integer>, Tuple2<String,
        Integer>>
        mappingFunc =
        new Function3<String, org.apache.spark.api.java.Optional<Integer>, State<Integer>,
            Tuple2<String, Integer>>() {
          @Override
          public Tuple2<String, Integer> call(String word,
              org.apache.spark.api.java.Optional<Integer> one, State<Integer> state) {
            int sum = one.orElse(0) + (state.exists() ? state.get() : 0);
            Tuple2<String, Integer> output = new Tuple2<>(word, sum);
            state.update(sum);
            return output;
          }
        };

    // DStream made of get cumulative counts that get updated in every batch
    JavaMapWithStateDStream<String, Integer, Integer, Tuple2<String, Integer>> stateDstream =
        wordsDstream.mapWithState(StateSpec.function(mappingFunc).initialState(initialRDD));

    stateDstream.print();

    return ssc;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 7) {
      System.err.println(
          "Usage: JavaKafkaWordCount <zkQuorum> <group> <topics> <numThreads> <batchSize> "
              + "<outputPath> <checkpoint>");
      System.exit(1);
    }

    final String zkQuorum = args[0];
    final String group = args[1];
    final String topics = args[2];
    final int numThreads = Integer.parseInt(args[3]);
    final int batchSize = Integer.parseInt(args[4]);
    final String outputPath = args[5];
    final String checkpointDirectory = args[6];

    // Function to create JavaStreamingContext without any output operations
    // (used to detect the new context)
    Function0<JavaStreamingContext> createContextFunc = new Function0<JavaStreamingContext>() {
      @Override
      public JavaStreamingContext call() {
        return createContext(zkQuorum, group, topics, numThreads, batchSize, outputPath,
            checkpointDirectory);
      }
    };

    JavaStreamingContext ssc =
        JavaStreamingContext.getOrCreate(checkpointDirectory, createContextFunc);
    ssc.start();
    ssc.awaitTermination();
  }
}