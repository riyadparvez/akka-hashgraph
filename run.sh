#!/usr/bin/env bash

#rm data-gen/tweets.txt
#python data-gen/get-tweets.py &> /dev/null & DATA_GEN_PID=$(echo $!)

#trap "kill -9 ${DATA_GEN_PID}" SIGINT EXIT
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

#java -cp ${DIR}/target/scala-2.11/akka-hashgraph_2.11-1.0.jar:${DIR}/target/scala-2.11/classes Main ${DIR}/tweet_input/tweets.txt ${DIR}/tweet_output/output.txt | tee log.txt
java -cp ${DIR}/target/scala-2.11/akka-hashgraph-assembly-1.0.jar Main ./tweet_input/tweets.txt ./tweet_output/output.txt | tee log.txt
