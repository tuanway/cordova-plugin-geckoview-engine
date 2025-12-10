var GeckoViewEngine = {
  getInfo: function () {
    return Promise.resolve({
      engine: 'geckoview',
      provider: 'Mozilla Gecko',
      embedded: true
    });
  }
};
module.exports = GeckoViewEngine;
