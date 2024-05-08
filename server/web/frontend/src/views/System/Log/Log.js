import React, {useContext, useEffect, useState} from 'react';

import {Table} from './components';
import {Card} from '@mui/material';

import {AppContext} from "adapter";

const LogPanel = () => {
    const [limit] = useState(10);
    const {api} = useContext(AppContext);
    const [page, setPage] = useState(1);
    const [total, setTotal] = useState(0);
    const [log, setLog] = useState([]);
    useEffect(() => {
        api.logList({page, pageSize: limit}).then(res => {
            if (res.status === 0) {
                setTotal(res.data.total);
                setLog(res.data.records);
            }
        })
    }, [page, limit, api]);

    return (
        <Card>
            <Table
                data={log}
                total={total}
                rowsPerPage={limit}
                pageState={[page, setPage]}/>
        </Card>
    );
};

export default LogPanel;
