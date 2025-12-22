
- When working in a namespace (e.g. `administration`) never touch sources from another namespace (e.g. `audittrail`).
- Do not touch source files that are in the following namespaces:
 - `*.datasets`
 - `*.proxies`
- When changing Mendix Java actions (those a subclass of `com.mendix.systemwideinterfaces.core.UserAction`):
 - Only change: 
  - Between `// BEGIN USER CODE` and `// END USER CODE`
  - Between `// BEGIN EXTRA CODE` and `// END EXTRA CODE`
  - Imports
   
- When logging:
 - Use an instance of `com.mendix.logging.ILogNode`
 - Instantiate it in a static block like `private static final ILogNode LOGGER = Core.getLogger(nameOfLogger);`
 - The name of the logger should be the top level namespace but in camel case. So a `ILogNode` in `audittrail.actions` should be named `AuditTrail`

- Logic re-used over actions should be placed in a namespace called `implementation`, e.g. `administration.implementation`
