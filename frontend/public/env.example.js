// Copy this file to env.js and replace the placeholders with deployment values.
// STRIPE_PUBLISHABLE_KEY must come from the same Stripe account as payment-service.
// SUPPORT_EMAIL is the maintainer contact advertised on /support/contact
// (TASK-P16-004 owner gate). Leave it as the placeholder until the project owner
// supplies the intended channel; the contact page shows an explicit
// configuration gate while unconfigured instead of a real address.
window.__env = window.__env || {};
window.__env.STRIPE_PUBLISHABLE_KEY = 'pk_test_replace_with_your_publishable_key';
window.__env.SUPPORT_EMAIL = 'replace-with-maintainer-address@example.com';
