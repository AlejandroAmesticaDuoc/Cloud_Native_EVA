export const environment = {
  production: false,

  msal: {
    clientId: '695de9f3-430f-4388-b7a3-354722064a35',
    tenantId: 'f4695429-59bd-41b7-a2fd-021509ca7488',
    redirectUri: 'http://localhost:4200/auth/callback',

    apiScope:
      'api://1dac46e3-fd25-4382-871e-7f7fd65ec8c7/pedidos360.access'
  },

  api: {
    baseUrl: 'http://localhost:8080/api/v1'
  }
};