CC ?= cc
CFLAGS ?= -O2 -pipe
CFLAGS += -std=c11 -Wall -Wextra -Werror

.PHONY: all arm64-static clean install test

all: avf-agent

avf-agent: avf-agent.c desktop_protocol.c desktop_protocol.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ avf-agent.c desktop_protocol.c

desktop-protocol-test: desktop_protocol_test.c desktop_protocol.c desktop_protocol.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ desktop_protocol_test.c desktop_protocol.c

test: desktop-protocol-test
	./desktop-protocol-test

arm64-static:
	$(MAKE) clean
	$(MAKE) CC=aarch64-linux-gnu-gcc LDFLAGS=-static avf-agent

install: avf-agent
	install -Dm0755 avf-agent $(DESTDIR)/usr/local/sbin/avf-agent
	install -Dm0644 ../../docs/guest/avf-agent.service \
		$(DESTDIR)/usr/lib/systemd/system/avf-agent.service
	install -Dm0755 avf-desktop $(DESTDIR)/usr/local/sbin/avf-desktop
	install -Dm0644 ../../docs/guest/avf-desktop@.service \
		$(DESTDIR)/usr/lib/systemd/system/avf-desktop@.service

clean:
	rm -f avf-agent desktop-protocol-test
