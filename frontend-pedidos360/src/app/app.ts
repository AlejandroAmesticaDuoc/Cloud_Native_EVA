import { Component, OnInit } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { MsalService } from '@azure/msal-angular';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App implements OnInit {

  constructor(
    private readonly authService: MsalService,
    private readonly router: Router
  ) {}

  ngOnInit(): void {
    this.authService.handleRedirectObservable().subscribe({
      next: (result) => {

        if (result?.account) {

          this.authService.instance.setActiveAccount(result.account);

          console.log('Login exitoso:', result.account.username);

          this.router.navigate(['/login']);
        }

        if (!this.authService.instance.getActiveAccount()) {
          const accounts = this.authService.instance.getAllAccounts();

          if (accounts.length > 0) {
            this.authService.instance.setActiveAccount(accounts[0]);
          }
        }
      },

      error: (error) => {
        console.error('Error procesando autenticación:', error);
      }
    });
  }
}