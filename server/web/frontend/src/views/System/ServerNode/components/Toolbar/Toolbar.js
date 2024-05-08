import React from 'react';
import {SearchInput} from 'components';

import clsx from 'clsx';
import {createUseStyles} from "react-jss";

const useStyles = createUseStyles(theme => ({
    root: {},
    row: {
        height: '42px',
        display: 'flex',
        alignItems: 'center',
        marginTop: theme.spacing(1)
    },
    spacer: {
        flexGrow: 1
    },
    importButton: {
        marginRight: theme.spacing(1)
    },
    exportButton: {
        marginRight: theme.spacing(1)
    },
    searchInput: {},
    dialog: {
        width: theme.spacing(60)
    },
    dialogInput: {
        width: '100%'
    },
    ml: {
        marginLeft: theme.spacing(2)
    },
}));

const Toolbar = props => {
    const {className, onInputChange, setRefresh, ...rest} = props;

    const classes = useStyles();

    return (
        <div
            {...rest}
            className={clsx(classes.root, className)}
        >
            <div className={classes.row}>
                <SearchInput
                    className={classes.searchInput}
                    onChange={(v) => onInputChange(v)}
                    placeholder="请输入关键词进行查询"
                />
                <span className={classes.spacer}/>
            </div>
        </div>
    );
};

export default Toolbar;
