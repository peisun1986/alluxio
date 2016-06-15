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

import com.google.common.io.Files;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.Optional;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.Function3;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.api.java.function.VoidFunction2;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.State;
import org.apache.spark.streaming.StateSpec;
import org.apache.spark.streaming.Time;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaMapWithStateDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaPairReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.dstream.InternalMapWithStateDStream;
import org.apache.spark.streaming.kafka.KafkaUtils;
import scala.Tuple2;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class StatefulKafkaWordCount2 {
  private static final Pattern SPACE = Pattern.compile(" ");

  public static void main(String[] args) throws Exception {
    if (args.length < 7) {
      System.err.println(
          "Usage: JavaKafkaWordCount <zkQuorum> <group> <topics> <numThreads> <batchSize> "
              + "<outputPath> <checkpoint>");
      System.exit(1);
    }

    Logger.getRootLogger().setLevel(Level.ERROR);

    final String zkQuorum = args[0];
    final String group = args[1];
    final String topics = args[2];
    final int numThreads = Integer.parseInt(args[3]);
    final int batchSize = Integer.parseInt(args[4]);
    final String outputPath = args[5];
    final String checkpointDirectory = args[6];

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
    // Just to increase the amount of data to checkpoint.
    messages.checkpoint(Durations.seconds(batchSize));

    JavaDStream<String> lines = messages.map(new Function<Tuple2<String, String>, String>() {
      @Override
      public String call(Tuple2<String, String> tuple2) {
        return tuple2._2();
      }
    });
    lines.checkpoint(Durations.seconds(batchSize));

    JavaDStream<String> words = lines.flatMap(new FlatMapFunction<String, String>() {
      @Override
      public Iterator<String> call(String x) {
        return Arrays.asList(SPACE.split(x)).iterator();
      }
    });
    words.checkpoint(Durations.seconds(batchSize));

    // Initial state RDD input to mapWithState
    @SuppressWarnings("unchecked")
    List<Tuple2<String, Integer>> tuples =
        Arrays.asList(new Tuple2<>("hello", 1), new Tuple2<>("world", 1));
    JavaPairRDD<String, Integer> initialRDD = ssc.sparkContext().parallelizePairs(tuples);

    JavaPairDStream<String, Integer> wordsDstream =
        words.mapToPair(new PairFunction<String, String, Integer>() {
          @Override
          public Tuple2<String, Integer> call(String s) {
            return new Tuple2<>(s, 1);
          }
        }).reduceByKey(new Function2<Integer, Integer, Integer>() {
          @Override
          public Integer call(Integer i1, Integer i2) {
            return i1 + i2;
          }
        });
    wordsDstream.checkpoint(Durations.seconds(batchSize));

    wordsDstream.foreachRDD(new VoidFunction2<JavaPairRDD<String, Integer>, Time>() {
      @Override
      public void call(JavaPairRDD<String, Integer> rdd, Time time) throws IOException {
        String output = "Counts at time " + System.nanoTime() + " " + time + " " +
            rdd.values().reduce(new Function2<Integer, Integer, Integer>() {
          @Override
          public Integer call(Integer v1, Integer v2) throws Exception {
            return v1 + v2;
          }
        }).toString();
        System.out.println(output);
        System.out.println("Appending to " + outputFile.getAbsolutePath());
        Files.append(output + "\n", outputFile, Charset.defaultCharset());
      }
    });
    ssc.start();
    ssc.awaitTermination();
  }
}
