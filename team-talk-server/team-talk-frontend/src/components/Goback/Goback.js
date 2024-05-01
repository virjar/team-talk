import React, {useState} from 'react';
import {useHistory} from 'react-router-dom';
import {CardHeader, Grid, IconButton, Popover} from '@mui/material';
import {ArrowBackIos, Dehaze} from '@mui/icons-material';

import PropTypes from 'prop-types';
import {createUseStyles} from "react-jss";

const useStyles = createUseStyles((theme) => ({
    header: {
        flexDirection: 'row-reverse',
        alignItems: 'center',
        padding: theme.spacing(1),
    },
    backIcon: {
        margin: theme.spacing(1, 2, 0, 0),
    },
    headerButton: {
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingRight: theme.spacing(2),
    }
}));

const Goback = ({title, subheader, extra}) => {
    const history = useHistory();
    const classes = useStyles();

    const [anchorEl, setAnchorEl] = useState(null);

    const handleClick = (event) => {
        setAnchorEl(event.currentTarget);
    };

    const handleClose = () => {
        setAnchorEl(null);
    };

    const selfHeader = (
        <CardHeader
            className={classes.header}
            action={
                <IconButton
                    onClick={() => history.go(-1)}
                    color="primary"
                    aria-label="back"
                    className={classes.backIcon}>
                    <ArrowBackIos style={{fontSize: 20}}/>
                </IconButton>
            }
            title={title}
            subheader={subheader}
        />
    );

    if (extra) {
        return (
            <Grid className={classes.headerButton}>
                {selfHeader}
                <IconButton onClick={handleClick}>
                    <Dehaze/>
                </IconButton>
                <Popover
                    open={Boolean(anchorEl)}
                    anchorEl={anchorEl}
                    onClose={handleClose}
                    anchorOrigin={{
                        vertical: 'bottom',
                        horizontal: 'center',
                    }}
                    transformOrigin={{
                        vertical: 'top',
                        horizontal: 'center',
                    }}
                >
                    {extra}
                </Popover>
            </Grid>
        )
    }
    return selfHeader;
};

Goback.propTypes = {
    title: PropTypes.string.isRequired
};

export default Goback;
