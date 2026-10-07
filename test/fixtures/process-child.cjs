const {spawn} = require('node:child_process');
switch (process.argv[2]) {
  case 'error': console.error('private-input-password'); process.exit(1); break;
  case 'orphan': spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], {stdio:'inherit'}); process.exit(0); break;
  case 'echo': process.stdin.pipe(process.stdout); break;
  case 'flood': process.stdout.write('x'.repeat(100000)); break;
  case 'tree': {
    const child = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], {stdio:'ignore'});
    console.log(child.pid); setInterval(()=>{},1000); break;
  }
  case 'sleep': process.on('SIGTERM',()=>{}); setInterval(()=>{},1000); break;
}
