"""Extract the paired server's actual CRTC selection for the small policy harness."""
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text()
start = source.index('static RRCrtcPtr\npresent_scmd_get_crtc(')
end = source.index('\nstatic uint32_t\npresent_scmd_query_capabilities', start)
output = Path(sys.argv[2])
output.mkdir(parents=True, exist_ok=True)
(output / 'function-under-test.h').write_text(source[start:end] + '\n')
