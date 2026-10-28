import { api } from './ns/api';
import { cache } from './ns/cache';
import { cacheEtat } from './ns/cacheEtat';
import { cacheex } from './ns/cacheex';
import { catalog } from './ns/catalog';
import { cluster } from './ns/cluster';
import { common } from './ns/common';
import { components } from './ns/components';
import { connectors } from './ns/connectors';
import { cwCycle } from './ns/cwCycle';
import { dcw } from './ns/dcw';
import { general } from './ns/general';
import { listeners } from './ns/listeners';
import { login } from './ns/login';
import { logs } from './ns/logs';
import { overview } from './ns/overview';
import { profiles } from './ns/profiles';
import { proxys } from './ns/proxys';
import { relais } from './ns/relais';
import { shell } from './ns/shell';
import { users } from './ns/users';

export const NAMESPACES = {
  api,
  cache,
  cacheEtat,
  cacheex,
  catalog,
  cluster,
  common,
  components,
  connectors,
  cwCycle,
  dcw,
  general,
  listeners,
  login,
  logs,
  overview,
  profiles,
  proxys,
  relais,
  shell,
  users,
} as const;

export type MessageKey = {
  [N in keyof typeof NAMESPACES]: `${N & string}.${keyof (typeof NAMESPACES)[N]['en'] & string}`;
}[keyof typeof NAMESPACES];
