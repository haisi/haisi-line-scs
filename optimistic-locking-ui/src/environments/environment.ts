import { QdAppEnvironment } from '@quadrel-enterprise-ui/framework';

/**
 * Single environment file rather than the classic dev/prod pair: the backend is always same-origin
 * (the Angular CLI dev-server proxy in `ng serve`, the embedded static classpath resources in
 * `mvnw spring-boot:run`/the packaged jar), so there's no distinct API base URL to swap.
 */
export const appEnvironment: QdAppEnvironment = {
  production: false,
  disableBackendNotifications: true,
};
