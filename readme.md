lucene-solr git commits project
===============================

Simple project used to push the commit data taken from lucene-solr GIT
repository and push it towards a solr server.
This simple project is actually used only to collect training data for an
experiment with the Solr JDBC driver used via Apache Zeppelin. 

For the purpose of this test solr 6.2.1 was used.

Once the data is posted and commited to the solrgit collection, it can
be analyzed via a Zeppelin notebook



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
