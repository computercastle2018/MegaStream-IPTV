// Isolated runtime diagnosis with the same encryption identity as the product.
const { app } = require('electron');
app.setName('megastream-desktop');
app.setPath('userData', process.env.TEST_PROFILE_PATH);
require('./main.cjs');
