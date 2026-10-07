export class TestEventSource extends EventTarget {
  static instances: TestEventSource[] = [];
  closed = false;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(public readonly url: string) {
    super();
    TestEventSource.instances.push(this);
  }
  close() {
    this.closed = true;
  }
  emit(name: string, body: unknown) {
    this.dispatchEvent(new MessageEvent(name, { data: JSON.stringify(body) }));
  }
}
