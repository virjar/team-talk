import React, {useEffect, useState} from "react";

import {Paper, Tab, Tabs} from "@mui/material";
import Config from "./Config";
import Log from "./Log";
import ProxyNode from "./ServerNode";
import BuildInfo from "./BuildInfo"
import configs from "config";
import {createUseStyles} from "react-jss";

const useStyles = createUseStyles(theme => ({
    root: {
        flexGrow: 1,
        padding: theme.spacing(3)
    },
    content: {
        marginTop: theme.spacing(2)
    }
}));

function TabPanel(props) {
    const {children, value, index} = props;
    return value === index ? children : null;
}

const systemDashboardConfigTabKey = configs.app + "-system-dashboard-tab";

function System() {
    const classes = useStyles();

    let initValue = Number(localStorage.getItem(systemDashboardConfigTabKey)) || 0;
    const [value, setValue] = useState(initValue);
    useEffect(() => {
        localStorage.setItem(systemDashboardConfigTabKey, value + "");
    }, [value])


    const handleChange = (event, val) => {
        setValue(val);
    };

    return (
        <div className={classes.root}>
            <Paper>
                <Tabs
                    value={value}
                    indicatorColor="primary"
                    textColor="primary"
                    onChange={handleChange}
                >
                    <Tab label="系统设置"/>
                    <Tab label="服务器节点"/>
                    <Tab label="用户操作日志"/>
                    <Tab label="构建信息"/>
                </Tabs>
                <div className={classes.content}>
                    <TabPanel value={value} index={0}>
                        <Config/>
                    </TabPanel>
                    <TabPanel value={value} index={1}>
                        <ProxyNode/>
                    </TabPanel>
                    <TabPanel value={value} index={2}>
                        <Log/>
                    </TabPanel>
                    <TabPanel value={value} index={3}>
                        <BuildInfo/>
                    </TabPanel>
                </div>
            </Paper>
        </div>
    );
}

export default System;
