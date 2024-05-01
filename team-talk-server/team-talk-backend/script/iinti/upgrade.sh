#!/usr/bin/env bash

cd `dirname $0`
script_dir=`pwd`

# enter workspace
cd ..

# backup config
cp conf/application.properties conf/application.properties.backup

echo "download assets"
curl -o TeamTalkMain.zip https://oss.iinti.cn/teamTalk/TeamTalkMain.zip

echo "unzip archive in teamTalk server ..."

unzip -q -o -d . TeamTalkMain.zip

cp conf/application.properties.backup conf/application.properties

echo "bootstrap app"
sh bin/startup.sh