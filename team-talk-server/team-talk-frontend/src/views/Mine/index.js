import React from 'react';
import {UserDashboard,} from './components';
import {Divider} from "@mui/material";
import SetLoginPassword from "./components/SetLoginPassword";
import {createUseStyles} from "react-jss";

const useStyles = createUseStyles(theme => ({
    root: {
        padding: theme.spacing(4)
    }
}));

const Mine = () => {
    const classes = useStyles();


    return (
        <div className={classes.root}>
            <UserDashboard/>
            <Divider/>
            <SetLoginPassword/>
            <Divider/>
        </div>
    );
};

export default Mine;
