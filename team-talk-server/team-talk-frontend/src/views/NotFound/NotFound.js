import React from 'react';
import {Grid, Typography} from '@mui/material';
import {createUseStyles} from "react-jss";

const useStyles = createUseStyles(theme => ({
    root: {
        padding: theme.spacing(4)
    },
    content: {
        paddingTop: 150,
        textAlign: 'center'
    },
    image: {
        marginTop: 50,
        display: 'inline-block',
        maxWidth: '100%',
        width: 560
    }
}));

const NotFound = () => {
    const classes = useStyles();

    return (
        <div className={classes.root}>
            <Grid
                container
                justifyContent="center"
                spacing={4}
            >
                <Grid
                    item
                    lg={6}
                    xs={12}
                >
                    <div className={classes.content}>
                        <Typography variant="h1">
                            404: 未找到页面
                        </Typography>
                        <Typography variant="subtitle2">
                            请打开正确的 url
                        </Typography>
                        <img
                            alt="Under development"
                            className={classes.image}
                            src="/images/undraw_page_not_found_su7k.svg"
                        />
                    </div>
                </Grid>
            </Grid>
        </div>
    );
};

export default NotFound;
