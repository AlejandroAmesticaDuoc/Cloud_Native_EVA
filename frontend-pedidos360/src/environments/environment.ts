export const environment = {
  production: false,

  msal: {
    clientId: '81d927fc-c3b2-4231-9bfc-7cd55dc36411',
    tenantId: 'dc99df57-acaa-43a4-bd69-f6abc34fc272',
    redirectUri: 'https://52.200.101.5/auth/callback',

    apiScope: 'api://1dac46e3-fd25-4382-871e-7f7fd65ec8c7/pedidos360.access'
  },

  api: {
    baseUrl: 'https://52.200.101.5:8080/api/v1'
  }
};
