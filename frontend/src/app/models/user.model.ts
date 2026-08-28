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
  app_metadata?: {
    roles?: string[];
    [key: string]: unknown;
  };
  user_metadata?: {
    name?: string;
    roles?: string[];
    [key: string]: unknown;
  };
  exp?: number;
  iat?: number;
}
