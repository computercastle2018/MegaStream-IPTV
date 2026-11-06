/** Node 22+ removed the legacy `util.is*` type checks that @webos-tools/cli
 *  still calls. This shim restores them so ares-* keep working on modern Node. */
const util = require('node:util');
if (typeof util.isDate !== 'function') {
  util.isDate = (v) => v instanceof Date;
}
if (typeof util.isArray !== 'function') {
  util.isArray = Array.isArray;
}
if (typeof util.isNumber !== 'function') {
  util.isNumber = (v) => typeof v === 'number';
}
if (typeof util.isString !== 'function') {
  util.isString = (v) => typeof v === 'string';
}
if (typeof util.isFunction !== 'function') {
  util.isFunction = (v) => typeof v === 'function';
}
if (typeof util.isObject !== 'function') {
  util.isObject = (v) => v !== null && typeof v === 'object';
}
if (typeof util.isNull !== 'function') {
  util.isNull = (v) => v === null;
}
if (typeof util.isUndefined !== 'function') {
  util.isUndefined = (v) => v === undefined;
}
if (typeof util.isRegExp !== 'function') {
  util.isRegExp = (v) => v instanceof RegExp;
}
if (typeof util.isError !== 'function') {
  util.isError = (v) => v instanceof Error;
}
if (typeof util.isBoolean !== 'function') {
  util.isBoolean = (v) => typeof v === 'boolean';
}
