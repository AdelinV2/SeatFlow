export interface UserProfile {
  id: string;
  email: string;
  name?: string;
  roles: string[];
  phone?: string;
  createdAt?: string;
}

export interface JwtClaims {
  sub: string;
  email?: string;
  name?: string;
  roles?: string[];
  exp?: number;
  iat?: number;
}
