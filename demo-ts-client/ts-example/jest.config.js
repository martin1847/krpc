/** @type {import('ts-jest/dist/types').InitialOptionsTsJest} */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'jsdom',//'node'
  testRegex: "(/__tests__/.*|(\\.|/)(test|spec))\\.tsx?$"
};