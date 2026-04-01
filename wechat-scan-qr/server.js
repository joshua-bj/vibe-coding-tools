require('dotenv').config();
const express = require('express');
const axios = require('axios');
const crypto = require('crypto');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;

// Proxy configuration for China
const HTTPS_PROXY = process.env.HTTPS_PROXY || null;
const axiosConfig = HTTPS_PROXY ? {
  proxy: {
    host: HTTPS_PROXY.split(':')[0],
    port: parseInt(HTTPS_PROXY.split(':')[1])
  }
} : {};

console.log('[DEBUG] Proxy configured:', HTTPS_PROXY || 'None');

// Serve static files
app.use(express.static('public'));
app.use(express.json());

// Serve WeChat verification files (for JS interface security domain setup)
// Put the MP_verify_xxxxx.txt file in the public folder
app.get('/MP_verify_*', (req, res) => {
  const fileName = req.path.substring(1); // Remove leading /
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
    console.log('[DEBUG] Using cached access token');
    return accessTokenCache.token;
  }

  console.log('[DEBUG] Requesting new access token from WeChat API...');
  console.log('[DEBUG] AppID:', WECHAT_APP_ID);
  console.log('[DEBUG] AppSecret:', WECHAT_APP_SECRET ? `${WECHAT_APP_SECRET.substring(0, 8)}...` : 'NOT SET');

  try {
    const url = `https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=${WECHAT_APP_ID}&secret=${WECHAT_APP_SECRET}`;
    console.log('[DEBUG] Request URL:', url.replace(WECHAT_APP_SECRET, 'SECRET_HIDDEN'));

    const response = await axios.get(url);

    console.log('[DEBUG] WeChat API Response:', JSON.stringify(response.data, null, 2));

    if (response.data.access_token) {
      accessTokenCache.token = response.data.access_token;
      // Cache for 7000 seconds (less than 7200 to be safe)
      accessTokenCache.expiresAt = Date.now() + 7000 * 1000;
      console.log('[DEBUG] Access token obtained successfully');
      console.log('[DEBUG] Token:', response.data.access_token.substring(0, 20) + '...');
      console.log('[DEBUG] Expires in:', response.data.expires_in, 'seconds');
      return response.data.access_token;
    } else {
      console.error('[DEBUG] Failed to get access token');
      console.error('[DEBUG] Error code:', response.data.errcode);
      console.error('[DEBUG] Error message:', response.data.errmsg);
      throw new Error(`${response.data.errmsg} (code: ${response.data.errcode})`);
    }
  } catch (error) {
    console.error('[DEBUG] Error getting access token:', error.message);
    if (error.response) {
      console.error('[DEBUG] Response status:', error.response.status);
      console.error('[DEBUG] Response data:', error.response.data);
    }
    throw error;
  }
}

/**
 * Get JSAPI ticket
 */
async function getJsApiTicket() {
  if (ticketCache.ticket && Date.now() < ticketCache.expiresAt) {
    console.log('[DEBUG] Using cached jsapi ticket');
    return ticketCache.ticket;
  }

  console.log('[DEBUG] Requesting new jsapi ticket from WeChat API...');

  try {
    const accessToken = await getAccessToken();
    const url = `https://api.weixin.qq.com/cgi-bin/ticket/getjsapi?access_token=${accessToken}&type=jsapi`;
    console.log('[DEBUG] Ticket request URL:', url.substring(0, 100) + '...');

    const response = await axios.get(url);

    console.log('[DEBUG] WeChat Ticket API Response:', JSON.stringify(response.data, null, 2));

    if (response.data.ticket) {
      ticketCache.ticket = response.data.ticket;
      // Cache for 7000 seconds
      ticketCache.expiresAt = Date.now() + 7000 * 1000;
      console.log('[DEBUG] Jsapi ticket obtained successfully');
      console.log('[DEBUG] Ticket:', response.data.ticket.substring(0, 30) + '...');
      console.log('[DEBUG] Expires in:', response.data.expires_in, 'seconds');
      return response.data.ticket;
    } else {
      console.error('[DEBUG] Failed to get jsapi ticket');
      console.error('[DEBUG] Error code:', response.data.errcode);
      console.error('[DEBUG] Error message:', response.data.errmsg);
      throw new Error(`${response.data.errmsg} (code: ${response.data.errcode})`);
    }
  } catch (error) {
    console.error('[DEBUG] Error getting jsapi ticket:', error.message);
    if (error.response) {
      console.error('[DEBUG] Response status:', error.response.status);
      console.error('[DEBUG] Response data:', error.response.data);
    }
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

  // Log the URL being used for signature
  console.log('\n' + '='.repeat(50));
  console.log('WeChat Config Request');
  console.log('='.repeat(50));
  console.log('Page URL:', url);

  // Extract domain for display
  try {
    const urlObj = new URL(url);
    console.log('Domain:', urlObj.hostname);
    console.log('Port:', urlObj.port || '(default)');
  } catch (e) {
    console.log('Warning: Could not parse URL');
  }

  // Demo mode - return mock config if credentials not set
  if (!WECHAT_APP_ID || !WECHAT_APP_SECRET) {
    console.log('Demo mode: Returning mock WeChat config');
    console.log('='.repeat(50));
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

    console.log('AppID:', WECHAT_APP_ID);
    console.log('Timestamp:', timestamp);
    console.log('NonceStr:', nonceStr);
    console.log('Signature:', signature);
    console.log('='.repeat(50));

    res.json({
      appId: WECHAT_APP_ID,
      timestamp,
      nonceStr,
      signature
    });
  } catch (error) {
    console.error('Error generating WeChat config:', error);
    res.status(500).json({ error: 'Failed to generate WeChat config' });
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
    console.log('Proxy:', HTTPS_PROXY || 'Not configured');
    console.log('');
    console.log('If you see "invalid url" error, set HTTP_PROXY=127.0.0.1:7890');
  }
  console.log('='.repeat(50));
});
