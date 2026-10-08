// Replace these only when the owner supplies approved launch details.
export const site = {
  domain: 'parrotapp.dev',
  supportEmail: 'rok@parrotapp.dev',
  legalName: 'Lunaria',
  address: '[ADDRESS]',
};

export const contactHref = `mailto:${site.supportEmail}`;
export const earlyAccessHref = `${contactHref}?subject=${encodeURIComponent('Parrot early access')}`;
