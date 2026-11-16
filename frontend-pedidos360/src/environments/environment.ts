export const environment = {
  production: false,

  msal: {
    clientId: '81d927fc-c3b2-4231-9bfc-7cd55dc36411',
    tenantId: 'dc99df57-acaa-43a4-bd69-f6abc34fc272',
    redirectUri: 'https://debating-shininess-manned.ngrok-free.dev/auth/callback',

    apiScope: 'api://81d927fc-c3b2-4231-9bfc-7cd55dc36411/pedidos360.access'
  },

  api: {
    baseUrl: `${window.location.origin}/api/v1`
  }
};
