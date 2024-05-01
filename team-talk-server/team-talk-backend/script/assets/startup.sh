#!/usr/bin/env bash
echo 'shutdown teamTalk  server'
now_dir=`pwd`

cd `dirname $0`
script_dir=`pwd`


function getPid(){
    echo $(ps -ef | grep "TeamTalkMain" | grep -v "grep" | grep -v "startup.sh" | awk '{print $2}')
}
remote_pid=`getPid`

echo remote_pid:${remote_pid}
if [[ -n "${remote_pid}" ]] ;then
    echo kill pid ${remote_pid}
    kill -9 ${remote_pid}
fi

echo "start teamTalk server"
sleep 2
remote_pid=`getPid`
if [[ -n "${remote_pid}" ]] ;then
    #   被supervisor自动守护
    exit 0
fi


std_log=../logs
if [[ ! -d ${std_log} ]] ;then
  mkdir ${std_log}
fi

# 本台服务器独立的配置
addition=''
if [[ -f ../conf/addition.txt ]] ;then
   addition=`cat ../conf/addition.txt`
fi
echo "addition param:${addition}"

# 如果存在这个文件，那么证明有新的环境变量需要定义
if [[ -f ../conf/TeamTalkMain.rc ]] ;then
    source ../conf/TeamTalkMain.rc
fi

nohup sh TeamTalkMain.sh -DLogbackDir=${std_log}  ${addition}  >> ${std_log}/std.log  2>&1 &

sleep 2

remote_pid=`getPid`

echo "remote pid:${remote_pid}"

