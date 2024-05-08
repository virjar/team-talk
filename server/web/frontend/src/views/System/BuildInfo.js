import React, {useContext} from "react";
import {AppContext} from "adapter";
import {Card, CardContent, Divider, Typography} from "@mui/material";

const BuildInfo = () => {
    const {systemInfo} = useContext(AppContext);
    const buildInfo = systemInfo.buildInfo;
    return (<Card>
        <CardContent>
            <Typography gutterBottom variant="h4">
                构建时间
            </Typography>
            <Typography gutterBottom variant="subtitle2">
                {buildInfo.buildTime}
            </Typography>
            <Divider/>
            <Typography gutterBottom variant="h4">
                编译主机
            </Typography>
            <Typography gutterBottom variant="subtitle2">
                {buildInfo.buildUser}
            </Typography>
            <Divider/>
            <Typography gutterBottom variant="h4">
                gitId
            </Typography>
            <Typography gutterBottom variant="subtitle2">
                {buildInfo.gitId}
            </Typography>

            <Divider/>
            <Typography gutterBottom variant="h4">
                版本
            </Typography>
            <Typography gutterBottom variant="subtitle2">
                {buildInfo.versionName}
            </Typography>

            <Divider/>
            <Typography gutterBottom variant="h4">
                版本号
            </Typography>
            <Typography gutterBottom variant="subtitle2">
                {buildInfo.versionCode}
            </Typography>
        </CardContent>
    </Card>);
}

export default BuildInfo;