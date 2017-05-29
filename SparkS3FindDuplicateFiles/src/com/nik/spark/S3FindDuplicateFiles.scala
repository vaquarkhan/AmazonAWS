package com.nik.spark

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import scala.collection.mutable.ListBuffer
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import scala.collection.mutable.ListBuffer
import com.amazonaws.services.s3.AmazonS3Client
import scala.collection.JavaConversions._
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.iterable.S3Objects
import org.apache.spark._
import org.apache.spark.SparkContext._
import org.apache.spark.sql._
import org.apache.log4j._
import org.apache.spark.sql.functions._
import org.apache.spark.rdd.RDD

case class FileChecksum(checkSum: String, filePath: String)

/**
 * Traverses s3 either entirely including all buckets or for a bucket only based on provided bucket name.
 */
class S3FindDuplicateFiles (awsAccessKey: String, awsSecretKey: String) extends java.io.Serializable {
   val S3Scheme = "s3n://"
  var S3FileSeparator = "/"

  /**
   * Initializes Spark Session object and also configures aws access key and secret keys in spark context.
   *
   * @return spark The spark session instance
   */
  def initSpark(): SparkSession = {
    val spark = SparkSession
      .builder
      .appName("SparkS3Integration")
      .master("local[*]")
      .getOrCreate()
    spark.sparkContext.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", awsAccessKey)
    spark.sparkContext.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", awsSecretKey)
    spark
  }

  /**
   * Initializes s3 client for supplied access key and secret key.
   *
   * @return s3Client The s3Client instance
   */
  def initS3Client(): AmazonS3Client = {
    val credential = new BasicAWSCredentials(awsAccessKey, awsSecretKey)
    val s3Client = new AmazonS3Client(credential)
    s3Client
  }

  def isRootPath(path: String): Boolean = {
    return path.equals("")
  }

  def isS3Directory(path: String): Boolean = {
    return path.endsWith("/")
  }
  
  implicit def rddToMD5Functions(rdd: RDD[String]) = new CheckSumCalculator(rdd)

  /**
   * It traverses entire s3, all buckets and explores all possible routes, while exploring it stores all paths as well
   *
   * @param s3Paths The list in which all routes are stored.
   */
  def exploreS3(s3Paths: ListBuffer[String]) {
    val s3Client = initS3Client()
    var buckets = s3Client.listBuckets()
    buckets.toSeq.foreach { bucket =>
      var s3Objects = S3Objects.withPrefix(s3Client, bucket.getName(), "")
      for (s3Object <- s3Objects) {
        exploreS3(s3Object.getBucketName(), s3Object.getKey, s3Paths)
      }
      checkDuplicateFiles(s3Paths)
    }
  }

  /**
   * It traverses bucket based on bucket name and prefix. While exploring it stores all paths as well. If any file is encountered then it uses spark to read the file.
   *
   * @param bucketName The bucket that is to be traversed
   * @param path The prefix
   * @param s3Paths The list in which all routes are stored
   */
  def exploreS3(bucketName: String, path: String, s3Paths: ListBuffer[String]) {
    val s3Client = initS3Client()
    if (isRootPath(path)) {
      var s3Objects = S3Objects.withPrefix(s3Client, bucketName, path)
      for (s3Object <- s3Objects) {
        exploreS3(s3Object.getBucketName(), s3Object.getKey(), s3Paths)
      }
    } else if (!isS3Directory(path)) {
      var absoluteS3Path = bucketName.concat(S3FileSeparator).concat(path)
      s3Paths += absoluteS3Path
    }
  }

  /**
   * Reads all files in the file path using Spark, distributes this to entire cluster, calculates checksum of each of the file using MD5.
   * Groups files based on checksum and find the corresponding count.
   * Order the result based on the # of duplication.
   * If recursive traverse is true then all files under supplied file path are considered for duplicate check.
   *
   * @param s3Paths The list of all s3 paths
   */
  def checkDuplicateFiles(s3Paths: ListBuffer[String]) {
    val spark = initSpark()
    import spark.implicits._

    val resultList = new ListBuffer[FileChecksum]
    s3Paths.foreach(filePath => {
      val fileRDD = initSpark().sparkContext.textFile(S3Scheme.concat(filePath))
      val checkSum = fileRDD.calculateCheckSum(10)
      val result = FileChecksum(checkSum, filePath)
      resultList += result
    })

    val resultRDD = spark.sparkContext.parallelize(resultList)
    val results = resultRDD.map(x => (x.checkSum, x.filePath))
      .groupByKey()
      .map(x => (x._1, (x._2, x._2.toList.length)))
      .sortBy(key => key._2._2, ascending = false)
      .collect()
     
     //display results
     results.foreach(record => (println(s"Files with checksum ${record._1} are ${record._2._1.toList} and duplication count is ${record._2._2}")))
  }
}