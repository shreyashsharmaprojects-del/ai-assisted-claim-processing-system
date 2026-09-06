#!/usr/bin/env node
// Renders keycloak/realm-export.template.json -> keycloak/realm-export.json, replacing the
// __ADJUSTER_PASSWORD__ / __SUPERVISOR_PASSWORD__ placeholders from the environment.
//
// Run with `node --env-file=.env keycloak/render-realm.mjs` (Node 22+), or after exporting
// the variables. The generated file is gitignored so provisioned user passwords never live
// in the working tree. Keycloak imports only the generated file (see docker-compose.yml).
import { readFileSync, writeFileSync } from 'node:fs';

const templatePath = new URL('./realm-export.template.json', import.meta.url);
const outPath = new URL('./realm-export.json', import.meta.url);

for (const name of ['ADJUSTER_PASSWORD', 'SUPERVISOR_PASSWORD']) {
  if (!process.env[name]) {
    console.error(`render-realm: ${name} is not set. Copy .env.example to .env and set it.`);
    process.exit(1);
  }
}

const template = readFileSync(templatePath, 'utf8');
const rendered = template
  .replaceAll('__ADJUSTER_PASSWORD__', process.env.ADJUSTER_PASSWORD)
  .replaceAll('__SUPERVISOR_PASSWORD__', process.env.SUPERVISOR_PASSWORD);

writeFileSync(outPath, rendered);
console.log('render-realm: wrote keycloak/realm-export.json');
