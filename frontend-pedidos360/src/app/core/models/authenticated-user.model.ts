export interface AuthenticatedUser {
  userId: string;
  subject: string;
  tenantId: string;
  username: string;
  displayName: string;
  roles: string[];
  scopes: string[];
}