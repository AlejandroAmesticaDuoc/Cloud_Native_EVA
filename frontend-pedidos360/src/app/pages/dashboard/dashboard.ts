import { Component, OnInit, inject, signal } from '@angular/core';

import { AuthApiService } from '../../core/services/auth-api.service';
import { AuthenticatedUser } from '../../core/models/authenticated-user.model';

@Component({
  selector: 'app-dashboard',
  imports: [],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.css'
})
export class Dashboard implements OnInit {

  private readonly authApi = inject(AuthApiService);

  user = signal<AuthenticatedUser | null>(null);

  loading = signal(true);

  error = signal('');

  ngOnInit(): void {

    this.authApi.getCurrentUser().subscribe({

      next: (user) => {

        console.log(
          'Usuario obtenido desde el BFF:',
          user
        );

        this.user.set(user);
        this.loading.set(false);
      },

      error: (error) => {

        console.error(
          'Error consumiendo /api/v1/auth/me:',
          error
        );

        this.error.set(
          'No fue posible obtener el usuario desde el BFF.'
        );

        this.loading.set(false);
      }
    });
  }
}