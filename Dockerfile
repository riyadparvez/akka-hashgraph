FROM anapsix/alpine-java
MAINTAINER Riyad Parvez <riyad.parvez@gmail.com>

RUN apk add --update wget git unzip

RUN mkdir -p /opt/InterviewTest/ && mkdir -p /opt/InterviewTest/data && cd /opt/InterviewTest && wget https://dl.bintray.com/sbt/native-packages/sbt/0.13.11/sbt-0.13.11.zip -O sbt-0.13.11.zip && unzip sbt-0.13.11.zip && chmod +x sbt/bin/sbt && sbt/bin/sbt compile

ADD Main.scala /opt/InterviewTest/
ADD build.sbt /opt/InterviewTest/
ADD data/products.txt /opt/InterviewTest/data/
ADD data/listings.txt /opt/InterviewTest/data/

WORKDIR /opt/InterviewTest/
ENV PATH $PATH:/opt/InterviewTest/sbt/bin
RUN echo 'export PATH=$PATH:/opt/InterviewTest/sbt/bin' >> /etc/profile
