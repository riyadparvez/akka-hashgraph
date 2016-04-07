#!/usr/bin/env bash

# example of the run script for running the word count

# I'll execute my programs, with the input directory tweet_input and output the files in the directory tweet_output
#python ./src/average_degree.py ./tweet_input/tweets.txt ./tweet_output/output.txt


#rm data-gen/tweets.txt
#python data-gen/get-tweets.py &> /dev/null & DATA_GEN_PID=$(echo $!)

#trap "kill -9 ${DATA_GEN_PID}" SIGINT EXIT
#DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"/..
DIR=/home/riyad/dev-src/coding-challenge/
#java -cp ${DIR}/target/scala-2.11/insight-test-assembly-1.0.jar Main ${DIR}/tweet_input/tweets.txt ${DIR}/tweet_output/output.txt | tee log.txt
java -cp ${DIR}/target/scala-2.11/insight-test-assembly-1.0.jar Main ./tweet_input/tweets.txt ./tweet_output/output.txt | tee log.txt
