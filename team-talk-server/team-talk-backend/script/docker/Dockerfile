FROM openjdk:8-alpine
ENV TZ=Asia/Shanghai
WORKDIR /opt/teamTalk
COPY ./TeamTalkMain.zip /opt/teamTalk/
RUN unzip TeamTalkMain.zip
ENTRYPOINT [ "sh","./bin/TeamTalk.sh" ]
