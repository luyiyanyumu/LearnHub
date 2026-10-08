import assert from 'node:assert/strict';
import test from 'node:test';
import { parseArgs } from '../bin/learnhub.mjs';

test('CLI keeps paths with spaces intact and maps installation flags', () => {
  assert.deepEqual(parseArgs(['start', '--home', 'C:\\My LearnHub', '--project', 'my-learnhub', '--image-prefix', 'localhost:5000/learnhub', '--web-port', '8890', '--backend-port', '18090', '--mysql-port', '3310', '--to', '1.0.0', '--no-open']), {
    command: 'start', options: {
      home: 'C:\\My LearnHub', project: 'my-learnhub', imagePrefix: 'localhost:5000/learnhub',
      webPort: '8890', backendPort: '18090', mysqlPort: '3310', to: '1.0.0', noOpen: true,
    },
  });
});

test('CLI rejects misspelled commands, duplicate options and ignored installation options', () => {
  for (const argv of [
    ['udpate'], ['start', '--home'], ['start', '--home', '--no-open'],
    ['start', '--home', 'first', '--home', 'second'],
    ['update', '--project', 'different'], ['status', '--to', '1.0.0'], ['start', 'extra'],
  ]) assert.throws(() => parseArgs(argv), Error);
});
