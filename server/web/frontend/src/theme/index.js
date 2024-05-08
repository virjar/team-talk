import {createTheme} from '@mui/material';
import {zhCN} from '@mui/material/locale';
import palette from './palette';

import typography from './typography';
import overrides from './overrides';

const theme = createTheme({
    palette,
    typography,
    overrides,
    zIndex: {
        appBar: 1200,
        drawer: 1100
    }
}, zhCN);

export default theme;
