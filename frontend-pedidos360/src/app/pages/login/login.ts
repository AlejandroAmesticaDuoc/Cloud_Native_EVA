import { Component } from '@angular/core';
import { MsalService } from '@azure/msal-angular';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-login',
  imports: [],
  templateUrl: './login.html',
  styleUrl: './login.css'
})
export class Login {

  constructor(private readonly msalService: MsalService) {}

  login(): void {
    this.msalService.loginRedirect({
      scopes: [
        environment.msal.apiScope
      ]
    });
  }

  logout(): void {
    this.msalService.logoutRedirect({
      postLogoutRedirectUri: 'http://localhost:4200/login'
    });
  }

  isLoggedIn(): boolean {
    return this.msalService.instance.getActiveAccount() !== null;
  }

  getUsername(): string {
    return this.msalService.instance.getActiveAccount()?.username ?? '';
  }

  verToken(): void {
    const account = this.msalService.instance.getActiveAccount();

    if (!account) {
      console.error('No existe una cuenta activa');
      return;
    }

    this.msalService.instance.acquireTokenSilent({
      account,
      scopes: [
        environment.msal.apiScope
      ]
    }).then((result) => {

      console.log('Access Token obtenido correctamente');

      const payload = result.accessToken.split('.')[1];

      const decodedPayload = JSON.parse(
        decodeURIComponent(
          atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
            .split('')
            .map((char) =>
              '%' + ('00' + char.charCodeAt(0).toString(16)).slice(-2)
            )
            .join('')
        )
      );

      console.log('Claims del Access Token:', decodedPayload);

      console.log('Scope:', decodedPayload.scp);
      console.log('Roles:', decodedPayload.roles);
      console.log('Audience:', decodedPayload.aud);
      console.log('Issuer:', decodedPayload.iss);

    }).catch((error) => {
      console.error('Error obteniendo Access Token:', error);
    });
  }
}