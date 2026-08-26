// Browser tests drive real IndexedDB and Web Locks, which the default 2s ceiling cuts short.
config.client = config.client || {};
config.client.mocha = config.client.mocha || {};
config.client.mocha.timeout = 60000;
