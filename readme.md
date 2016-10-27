lucene-solr git commits project
===============================

Simple project used to push the commit data taken from lucene-solr GIT
repository and push it towards a solr server.
This simple project is actually used only to collect test data for an
experiment with the Solr JDBC driver used via Apache Zeppelin. 

For the purpose of this test the software packages :

* apache solr 6.2.1 
* apache zeppelin 0.6.1

were used.

Once the data is posted and commited to the solrgit collection, it can
be analyzed via a Zeppelin notebook.

Below is presented a sample of one of the  documents from _solrgit_ collection:

```
{
        "id":"1b7a88f61ea44ecc873d7c7d135ce5c6ab88bb0a",
        "ticket":"LUCENE-7491",
        "message":"LUCENE-7491: fix merge exception if the same field has points in some segments but not in others\n",
        "commit_date":"2016-10-12T13:00:26Z",
        "commit_day":"2016-10-12T00:00:00Z",
        "commit_month":"2016-10-01T00:00:00Z",
        "commit_year":2016,
        "commiter_name":"Mike McCandless",
        "commiter_email":"mikemccand@apache.org",
        "author_name":"Mike McCandless",
        "author_email":"mikemccand@apache.org",
        "parents_ids":["6512d0c62024177cc5d6c8b7086faaa149565dfb"],
        "parents_count":1,
        "modified_files":["lucene/CHANGES.txt",
          "lucene/core/src/java/org/apache/lucene/codecs/PointsWriter.java",
          "lucene/core/src/java/org/apache/lucene/codecs/lucene60/Lucene60PointsWriter.java",
          "lucene/core/src/java/org/apache/lucene/index/FieldInfo.java",
          "lucene/test-framework/src/java/org/apache/lucene/index/BasePointsFormatTestCase.java"],
        "_version_":1549281927517700096
}
```

Below is presented a glimpse on the type of queries that can be done via Zeppelin


![commits per day](img/zeppelin-commits-per-day.png)
![commits per year](img/zeppelin-commits-per-year.png)
![commits per user](img/zeppelin-commits-per-user.png)
![commits per user per year](img/zeppelin-commits-per-user-per-year.png)



## Create the Solr collection:


```
bin/solr create_core -c solrgit -d /home/marius/java/solrgit/conf

```


## Delete all documents  from the Solr collection

```
curl http://localhost:8983/solr/solrgit/update\?stream.body\=\<delete\>\<query\>\*:\*\</query\>\</delete\>\&commit\=true
```


## Libraries used

The library used for collecting the data from the GIT commits was eclipse's jgit
and for posting the data towards Solr was solrj.



## Zeppelin Solr JDBC

Since version 6 of Solr has been introduced a JDBC driver which can be configured on Zeppelin
to run a subset of SQL queries on Solr.
 
 
Some informations on how to setup Solr JDBC driver on Apache Zeppelin can be found here:

* http://opensourceconnections.com/blog/2016/05/18/its_a_ballon_a_blimp_no_a_dirigible/
* https://www.linkedin.com/pulse/apache-solr-jdbc-zeppelin-incubating-kevin-risden


The notebook used for performing the queries presented above can be found at the location:

* ./zeppelin/notebook/solr-git-analysis.json


## Zeppelin & Spark & Solr

Zeppelin offers also the possibility to perform [Spark](http://spark.apache.org/) operations
on Solr via [spark-sql](http://spark.apache.org/) driver, but at the time of writing this
sample code, the spark-sql driver was causing issues because its current version (2.2.1) is compiled
with scala 2.10, but zeppelin on the other hand is compiled with scala 2.11


## Spark-Shell & Spark-Solr

The scripts that would have been included in the notebook to be executed via Zeppelin can be seen below.
By using Spark-Solr there can achieved more detailed analysis which are not available via solr jdbc queries.
One example in this direction would be analysis performed on multi-valued fields (e.g.: file containing most modifications).


In order to work with spark-solr within the spark-shell use the following command:

```
➜  spark-1.6.2-bin-hadoop2.6 bin/spark-shell --packages "com.lucidworks.spark:spark-solr:2.2.1
```

Note below the attribute `flatten_multivalued` which is used to tell spark not to flatten the multi-valued
fields retrieved from solr.

```
val options = Map("collection" -> "solrgit", "zkhost" -> "127.0.0.1:9983", "flatten_multivalued" -> "false")
val df = sqlContext.read.format("solr").options(options).load
```


### Tickets with the most commits

```
df.filter($"ticket".isNotNull)
  .groupBy("ticket").count()
  .sort(desc("count")).show
```

```
Array([LUCENE-3930,100], [LUCENE-3490,98], [LUCENE-2621,97], [LUCENE-3305,97], [LUCENE-5969,87], [LUCENE-3892,79], [LUCENE-4055,77], [LUCENE-4956,76], [LUCENE-2858,73], [SOLR-8029,71])
```


### Files containing the most changes

```
df.select("modified_files")
  .withColumn("modified_file", explode($"modified_files")).select("modified_file")
  .groupBy("modified_file").count()
  .sort(desc("count")).take(10)
```

```
Array([solr/CHANGES.txt,7045], [lucene/CHANGES.txt,4717], [CHANGES.txt,1918], [lucene/common-build.xml,843], [build.xml,777], [solr/core/src/java/org/apache/solr/cloud/ZkController.java,604], [lucene/core/src/java/org/apache/lucene/index/IndexWriter.java,599], [solr/build.xml,586], [lucene/build.xml,479], [lucene/test-framework/src/java/org/apache/lucene/util/LuceneTestCase.java,472])
```