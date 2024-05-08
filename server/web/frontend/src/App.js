import React from 'react';
import {Router} from 'react-router-dom';
import {createHashHistory} from 'history';
import {SnackbarProvider} from 'notistack';
import Adapter from './adapter';
import validate from 'validate.js';
import theme from './theme';
import 'react-perfect-scrollbar/dist/css/styles.css';
import './assets/scss/index.scss';
import validators from './common/validators';
import Routes from './Routes';
import {ThemeProvider} from "react-jss";

const hashHistory = createHashHistory();

validate.validators = {
    ...validate.validators,
    ...validators
};

function App() {

    return (
        <ThemeProvider theme={theme}>
            <SnackbarProvider maxSnack={3}>
                <Adapter>
                    <Router history={hashHistory}>
                        <Routes/>
                    </Router>
                </Adapter>
            </SnackbarProvider>
        </ThemeProvider>
    );

}

export default App;
