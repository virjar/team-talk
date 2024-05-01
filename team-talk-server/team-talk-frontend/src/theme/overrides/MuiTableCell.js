import palette from '../palette';
import typography from '../typography';

const MuiTableCell = {
    root: {
        ...typography.body1,
        borderBottom: `1px solid ${palette.divider}`
    }
};

export default MuiTableCell;