#!/usr/bin/env bash
now_dir=`pwd`

if [ ! -d teamTalk_compose ]; then
   mkdir "teamTalk"
else
   rm -rf teamTalk/*
fi

cd teamTalk;

curl -o "teamTalk_compose.zip" "https://oss.iinti.cn/teamTalk/team-talk-compose.zip"

(unzip --help) </dev/null >/dev/null 2>&1 || {
  echo
  echo "no unzip cmd , please install unzip first: yum install -y unzip"
  exit 4
}


unzip -o teamTalk_compose.zip

cd team-talk-compose;

docker-compose down;

docker pull registry.cn-beijing.aliyuncs.com/iinti/teamTalk:team-talk-server-latest;

docker-compose up -d