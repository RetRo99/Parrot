// Replace these only when the owner supplies approved launch details.
export const site = {
  domain: 'parrotapp.dev',
  supportEmail: 'retar.rok@gmail.com',
  legalName: '[LEGAL NAME]',
  address: '[ADDRESS]',
};

export const contactHref = `mailto:${site.supportEmail}`;
export const earlyAccessHref = `${contactHref}?subject=${encodeURIComponent('Parrot early access')}`;
