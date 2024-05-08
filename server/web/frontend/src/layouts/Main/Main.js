import React, {useState} from 'react';
import PropTypes from 'prop-types';
import clsx from 'clsx';
import {useMediaQuery} from '@mui/material';

import {Footer, Sidebar, Topbar} from './components';
import {createUseStyles, useTheme} from "react-jss";

const useStyles = createUseStyles(theme => ({
    root: {
        paddingTop: 56,
        height: '100%',
        [theme.breakpoints.up('sm')]: {
            paddingTop: 64
        }
    },
    shiftContent: {
        paddingLeft: 240
    },
    content: {
        height: '100%',
        display: "flex",
        flexDirection: "column"
    }, container: {
        flex: 1
    }
}));

const Main = props => {
    const {children} = props;

    const classes = useStyles();
    const theme = useTheme();
    const isDesktop = useMediaQuery(theme.breakpoints.up('lg'), {
        defaultMatches: true
    });

    const [openSidebar, setOpenSidebar] = useState(false);

    const handleSidebarOpen = () => {
        setOpenSidebar(true);
    };

    const handleSidebarClose = () => {
        setOpenSidebar(false);
    };

    const shouldOpenSidebar = isDesktop ? true : openSidebar;

    return (
        <div
            className={clsx({
                [classes.root]: true,
                [classes.shiftContent]: isDesktop
            })}
        >
            <Topbar onSidebarOpen={handleSidebarOpen}/>
            <Sidebar
                onClose={handleSidebarClose}
                open={shouldOpenSidebar}
                variant={isDesktop ? 'persistent' : 'temporary'}
            />
            <main className={classes.content}>
                <div className={classes.container}>
                    {children}
                </div>
                <Footer/>
            </main>
        </div>
    );
};

Main.propTypes = {
    children: PropTypes.node
};

export default Main;
