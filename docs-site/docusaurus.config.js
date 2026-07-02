// @ts-check
import {themes as prismThemes} from 'prism-react-renderer';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

const GITHUB_REPO = 'https://github.com/martin1847/krpc';
const GITHUB_BLOB = `${GITHUB_REPO}/blob/dev`;

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'KRPC',
  tagline: 'Interface-first RPC for cloud-native services',
  favicon: 'img/favicon.ico',

  future: {
    v4: true, // Improve compatibility with the upcoming Docusaurus v4
  },

  // GitHub Pages: project site at https://martin1847.github.io/krpc/
  url: 'https://martin1847.github.io',
  baseUrl: '/krpc/',
  organizationName: 'martin1847',
  projectName: 'krpc',

  onBrokenLinks: 'throw',
  markdown: {
    hooks: {
      onBrokenMarkdownLinks: 'warn',
    },
  },

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          sidebarPath: './sidebars.js',
          editUrl: `${GITHUB_REPO}/tree/dev/docs-site/`,
        },
        blog: false, // this is a documentation site, not a blog
        theme: {
          customCss: './src/css/custom.css',
        },
      }),
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      colorMode: {
        respectPrefersColorScheme: true,
      },
      navbar: {
        title: 'KRPC',
        items: [
          {
            type: 'docSidebar',
            sidebarId: 'docsSidebar',
            position: 'left',
            label: 'Docs',
          },
          {
            href: `${GITHUB_BLOB}/SPEC.md`,
            label: 'SPEC',
            position: 'left',
          },
          {
            href: GITHUB_REPO,
            label: 'GitHub',
            position: 'right',
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Docs',
            items: [
              {label: 'Getting Started', to: '/docs/getting-started'},
              {label: 'Reference', to: '/docs/reference'},
            ],
          },
          {
            title: 'Source of Truth',
            items: [
              {label: 'SPEC.md', href: `${GITHUB_BLOB}/SPEC.md`},
              {label: 'Support Policy', href: `${GITHUB_BLOB}/docs/support-policy.md`},
              {label: 'Changelog', href: `${GITHUB_BLOB}/changelog.md`},
            ],
          },
          {
            title: 'More',
            items: [
              {label: 'GitHub', href: GITHUB_REPO},
              {label: 'Security', href: `${GITHUB_BLOB}/SECURITY.md`},
            ],
          },
        ],
        copyright: `Copyright © ${new Date().getFullYear()} KRPC. Built with Docusaurus.`,
      },
      prism: {
        theme: prismThemes.github,
        darkTheme: prismThemes.dracula,
        additionalLanguages: ['java', 'gradle', 'bash', 'json'],
      },
    }),
};

export default config;
