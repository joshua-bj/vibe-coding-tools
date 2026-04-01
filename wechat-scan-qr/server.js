require('dotenv').config();
const express = require('express');
const axios = require('axios');
const crypto = require('crypto');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;

// Serve static files
app.use(express.static('public'));
app.use(express.json());

// Serve WeChat verification files (for JS interface security domain setup)
app.get('/MP_verify_*', (req, res) => {
  const fileName = req.path.substring(1);
  const filePath = path.join(__dirname, 'public', fileName);
  res.sendFile(filePath, (err) => {
    if (err) {
      res.status(404).send('Verification file not found. Please add MP_verify_xxxxx.txt to public folder.');
    }
  });
});

// WeChat configuration
const WECHAT_APP_ID = process.env.WECHAT_APP_ID;
const WECHAT_APP_SECRET = process.env.WECHAT_APP_SECRET;

// Cache for access token
let accessTokenCache = {
  token: null,
  expiresAt: 0
};

// Cache for jsapi ticket
let ticketCache = {
  ticket: null,
  expiresAt: 0
};

/**
 * Get WeChat access token
 */
async function getAccessToken() {
  if (accessTokenCache.token && Date.now() < accessTokenCache.expiresAt) {
    return accessTokenCache.token;
  }

  try {
    const response = await axios.get(
      `https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=${WECHAT_APP_ID}&secret=${WECHAT_APP_SECRET}`
    );

    if (response.data.access_token) {
      accessTokenCache.token = response.data.access_token;
      accessTokenCache.expiresAt = Date.now() + 7000 * 1000;
      console.log('[INFO] Access token obtained successfully');
      return response.data.access_token;
    } else {
      throw new Error(`${response.data.errmsg} (code: ${response.data.errcode})`);
    }
  } catch (error) {
    console.error('[ERROR] Failed to get access token:', error.message);
    throw error;
  }
}

/**
 * Get JSAPI ticket
 */
async function getJsApiTicket() {
  if (ticketCache.ticket && Date.now() < ticketCache.expiresAt) {
    return ticketCache.ticket;
  }

  try {
    const accessToken = await getAccessToken();
    const response = await axios.get(
      `https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token=${accessToken}&type=jsapi`
    );

    if (response.data.ticket) {
      ticketCache.ticket = response.data.ticket;
      ticketCache.expiresAt = Date.now() + 7000 * 1000;
      console.log('[INFO] Jsapi ticket obtained successfully');
      return response.data.ticket;
    } else {
      throw new Error(`${response.data.errmsg} (code: ${response.data.errcode})`);
    }
  } catch (error) {
    console.error('[ERROR] Failed to get jsapi ticket:', error.message);
    throw error;
  }
}

/**
 * Generate signature for JS-SDK
 */
function generateSignature(ticket, nonceStr, timestamp, url) {
  const string1 = `jsapi_ticket=${ticket}&noncestr=${nonceStr}&timestamp=${timestamp}&url=${url}`;
  return crypto.createHash('sha1').update(string1).digest('hex');
}

/**
 * API endpoint to get WeChat JS-SDK config
 */
app.get('/api/wechat-config', async (req, res) => {
  const url = req.query.url;

  if (!url) {
    return res.status(400).json({ error: 'URL parameter is required' });
  }

  console.log('[INFO] Config request for:', url);

  // Demo mode - return mock config if credentials not set
  if (!WECHAT_APP_ID || !WECHAT_APP_SECRET) {
    console.log('[INFO] Running in demo mode');
    return res.json({
      appId: 'DEMO_APP_ID',
      timestamp: Math.floor(Date.now() / 1000),
      nonceStr: Math.random().toString(36).substring(2, 15),
      signature: 'demo_signature_placeholder',
      demoMode: true,
      message: 'This is demo mode. Set WECHAT_APP_ID and WECHAT_APP_SECRET in .env file for real usage.'
    });
  }

  try {
    const ticket = await getJsApiTicket();
    const timestamp = Math.floor(Date.now() / 1000);
    const nonceStr = Math.random().toString(36).substring(2, 15);
    const signature = generateSignature(ticket, nonceStr, timestamp, url);

    res.json({
      appId: WECHAT_APP_ID,
      timestamp,
      nonceStr,
      signature
    });
  } catch (error) {
    console.error('[ERROR] Failed to generate WeChat config:', error.message);
    res.status(500).json({ error: 'Failed to generate WeChat config', message: error.message });
  }
});

// Start server
app.listen(PORT, () => {
  console.log(`Server is running on http://localhost:${PORT}`);
  console.log('');
  console.log('='.repeat(50));
  console.log('WeChat QR Scanner Demo');
  console.log('='.repeat(50));
  if (!WECHAT_APP_ID || !WECHAT_APP_SECRET) {
    console.log('Running in DEMO mode');
    console.log('To use with real WeChat, create .env file with:');
    console.log('  WECHAT_APP_ID=your_app_id');
    console.log('  WECHAT_APP_SECRET=your_app_secret');
  } else {
    console.log('Running with WeChat App ID:', WECHAT_APP_ID);
  }
  console.log('='.repeat(50));
});
