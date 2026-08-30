import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { UserProfile } from '../models/user.model';

export interface UpdateProfileDto {
  name?: string;
  phone?: string;
}

@Injectable({ providedIn: 'root' })
export class UserApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/users';

  getProfile(): Observable<UserProfile> {
    return this.http.get<UserProfile>(`${this.baseUrl}/me`);
  }

  updateProfile(dto: UpdateProfileDto): Observable<UserProfile> {
    return this.http.put<UserProfile>(`${this.baseUrl}/me`, dto);
  }
}
