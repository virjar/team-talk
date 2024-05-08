import React, {useContext, useEffect, useState} from 'react';
import {Link as RouterLink, withRouter} from 'react-router-dom';
import {Button, Checkbox, Grid, Link, TextField, Typography} from '@mui/material';
import {AppContext} from 'adapter';
import PropTypes from 'prop-types';
import validate from 'validate.js';
import moment from 'moment';
import configs from 'config';
import {createUseStyles} from "react-jss";

const schema = {
    oa: {
        presence: {allowEmpty: false, message: '不能为空'},
        length: {
            maximum: 64
        }
    },
    password: {
        presence: {allowEmpty: false, message: '不能为空'},
        length: {
            maximum: 128
        }
    },
    policy: {
        presence: {allowEmpty: false, message: 'is required'},
        checked: true
    }
};

const useStyles = createUseStyles(theme => ({
    root: {
        backgroundColor: theme.palette.background.default,
        height: '100%'
    },
    grid: {
        height: '100%'
    },
    quoteContainer: {
        [theme.breakpoints.down('md')]: {
            display: 'none'
        }
    },
    quote: {
        backgroundColor: theme.palette.neutral,
        height: '100%',
        width: '100%',
        overflow: 'hidden',
        display: 'flex',
        alignItems: 'center'
    },
    quoteImg: {
        margin: '0 auto',
        height: '400px'
    },
    quoteIframe: {
        width: '100%',
        height: '300px'
    },
    quoteInner: {
        textAlign: 'center',
        flexBasis: '600px'
    },
    quoteText: {
        color: theme.palette.white,
        fontWeight: 300
    },
    name: {
        marginTop: theme.spacing(3),
        color: theme.palette.white
    },
    bio: {
        color: theme.palette.white
    },
    contentContainer: {},
    content: {
        height: '100%',
        display: 'flex',
        flexDirection: 'column'
    },
    contentHeader: {
        display: 'flex',
        alignItems: 'center',
        paddingTop: theme.spacing(5),
        paddingBototm: theme.spacing(2),
        paddingLeft: theme.spacing(2),
        paddingRight: theme.spacing(2)
    },
    logoImage: {
        marginLeft: theme.spacing(4)
    },
    contentBody: {
        flexGrow: 1,
        display: 'flex',
        alignItems: 'center',
        [theme.breakpoints.down('md')]: {
            justifyContent: 'center'
        }
    },
    form: {
        paddingLeft: 100,
        paddingRight: 100,
        paddingBottom: 125,
        flexBasis: 700,
        [theme.breakpoints.down('sm')]: {
            paddingLeft: theme.spacing(2),
            paddingRight: theme.spacing(2)
        }
    },
    title: {
        marginTop: theme.spacing(3)
    },
    socialButtons: {
        marginTop: theme.spacing(3)
    },
    socialIcon: {
        marginRight: theme.spacing(1)
    },
    sugestion: {
        marginTop: theme.spacing(2)
    },
    textField: {
        marginTop: theme.spacing(2)
    },
    signInButton: {
        margin: theme.spacing(2, 0)
    },
    policy: {
        marginTop: theme.spacing(1),
        display: 'flex',
        alignItems: 'center'
    },
    policyCheckbox: {
        marginLeft: '-14px'
    },
}));

const SignIn = props => {
    const {history} = props;
    const {setUser, api} = useContext(AppContext);

    const classes = useStyles();

    const [formState, setFormState] = useState({
        isValid: false,
        values: {},
        touched: {},
        errors: {}
    });

    useEffect(() => {
        const errors = validate(formState.values, schema);

        setFormState(formState => ({
            ...formState,
            isValid: !errors,
            errors: errors || {}
        }));
    }, [formState.values]);

    const handleChange = event => {
        event.persist();

        setFormState(formState => ({
            ...formState,
            values: {
                ...formState.values,
                [event.target.name]:
                    event.target.type === 'checkbox'
                        ? event.target.checked
                        : event.target.value
            },
            touched: {
                ...formState.touched,
                [event.target.name]: true
            }
        }));
    };

    const handleSignIn = event => {
        event.preventDefault();
        if (formState.isValid) {
            api.register({
                userName: formState.values.oa,
                password: formState.values.password,
            }).then(res => {
                if (res.status === 0) {
                    api.setStore(res.data);
                    setUser({
                        ...res.data,
                        time: moment(new Date()).format('YYYY-MM-DD HH:mm:ss')
                    });
                    history.push('/');
                }
            });
        }
    };

    const hasError = field =>
        !!(formState.touched[field] && formState.errors[field]);

    return (
        <div className={classes.root}>
            <Grid
                className={classes.grid}
                container
            >
                <Grid
                    className={classes.quoteContainer}
                    item
                    lg={5}
                >
                    <div className={classes.quote}>
                        <img className={classes.quoteImg} src="/images/logos/logo.svg" alt=""/>
                    </div>
                </Grid>
                <Grid
                    className={classes.content}
                    item
                    lg={7}
                >
                    <div className={classes.content}>
                        <div className={classes.contentBody}>
                            <form
                                className={classes.form}
                                onSubmit={handleSignIn}
                            >
                                <Typography
                                    className={classes.title}
                                    variant="h2"
                                >
                                    {configs.app}
                                </Typography>
                                <Typography
                                    color="textSecondary"
                                    gutterBottom
                                >
                                    账号注册
                                </Typography>
                                <TextField
                                    className={classes.textField}
                                    error={hasError('oa')}
                                    fullWidth
                                    helperText={
                                        hasError('oa') ? formState.errors.oa[0] : null
                                    }
                                    label="账号"
                                    name="oa"
                                    onChange={handleChange}
                                    type="text"
                                    value={formState.values.oa || ''}
                                    variant="outlined"
                                />
                                <TextField
                                    className={classes.textField}
                                    error={hasError('password')}
                                    fullWidth
                                    helperText={
                                        hasError('password') ? formState.errors.password[0] : null
                                    }
                                    label="密码"
                                    name="password"
                                    onChange={handleChange}
                                    type="password"
                                    value={formState.values.password || ''}
                                    variant="outlined"
                                />
                                <div className={classes.policy}>
                                    <Checkbox
                                        checked={formState.values.policy || false}
                                        className={classes.policyCheckbox}
                                        color="primary"
                                        name="policy"
                                        onChange={handleChange}
                                    />
                                    <Typography
                                        className={classes.policyText}
                                        color="textSecondary"
                                        variant="body1"
                                    >
                                        勾选即已阅读并同意{' '}
                                        <Link
                                            color="primary"
                                            component={RouterLink}
                                            to="#"
                                            underline="always"
                                            variant="h6"
                                        >
                                            {configs.app} 用户协议
                                        </Link>
                                    </Typography>
                                </div>
                                <Button
                                    className={classes.signInButton}
                                    color="primary"
                                    disabled={!formState.isValid}
                                    fullWidth
                                    size="large"
                                    type="submit"
                                    variant="contained"
                                >
                                    注册
                                </Button>
                                <Typography
                                    color="textSecondary"
                                    variant="body1"
                                >
                                    已有账号?{' '}
                                    <Link
                                        component={RouterLink}
                                        to="/sign-in"
                                        variant="h6"
                                    >
                                        去登录
                                    </Link>
                                </Typography>
                            </form>
                        </div>
                    </div>
                </Grid>
            </Grid>
        </div>
    );
};

SignIn.propTypes = {
    history: PropTypes.object
};

export default withRouter(SignIn);

