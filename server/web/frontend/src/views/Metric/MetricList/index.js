import React, {useContext, useEffect, useState} from 'react';

import {Table, Toolbar} from './components';
import {AppContext} from "adapter";
import {createUseStyles} from "react-jss";

const useStyles = createUseStyles(theme => ({
    root: {
        padding: theme.spacing(3)
    },
    content: {
        marginTop: theme.spacing(2)
    }
}));

const MetricList = () => {
    const classes = useStyles();
    const [metricList, setMetricList] = useState([]);
    const [page, setPage] = useState(1);
    const [limit] = useState(10);
    const [keyword, setKeyword] = useState('');
    const [refresh, setRefresh] = useState(+new Date());
    const {api} = useContext(AppContext);

    useEffect(() => {
        api.allMetricConfig().then(res => {
            if (res.status !== 0) {
                return;
            }
            setMetricList(res.data);
        })
    }, [api, refresh]);

    const showData = metricList.filter(item => {
        return JSON.stringify(item).includes(keyword);
    });

    return (
        <div className={classes.root}>
            <Toolbar onInputChange={(k) => {
                setKeyword(k);
                setPage(1);
            }} setRefresh={setRefresh}/>
            <div className={classes.content}>
                <Table
                    data={showData.slice((page - 1) * limit, page * limit)}
                    total={showData.length}
                    rowsPerPage={limit}
                    setRefresh={setRefresh}
                    pageState={[page, setPage]}/>
            </div>
        </div>
    );
};

export default MetricList;
