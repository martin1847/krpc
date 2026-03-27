
# RSA keys


The JOSE standard recommends a minimum RSA key size of 2048 bits.

To generate a 2048-bit RSA private + public key post for use in RSxxx and PSxxx signatures:


```bash
openssl genrsa 2048 -out rsa2048-backoffice-auth-test-1115.key-post.pem 
-----BEGIN PRIVATE KEY-----
...Base64 encoded key...
-----END PRIVATE KEY-----
```

# java read PEM

https://www.baeldung.com/java-read-pem-file-keys

## X.509 certificate (PEM header: `BEGIN CERTIFICATE`)
## X.509 SubjectPublicKeyInfo (PEM header: `BEGIN PUBLIC KEY`)

a standard defining the format of public-key certificates. So, this format describes a public key among other information.

## DER 
the most popular encoding format to store data like X.509 certificates, PKCS8 private keys in files. It's a binary encoding and the resulting content cannot be viewed with a text editor.

## PKCS8 , PEM header: `BEGIN PRIVATE KEY`
a standard syntax for storing private key information. The private key can be optionally encrypted using a symmetric algorithm.

* PKCS#1 RSAPublicKey (PEM header: `BEGIN RSA PUBLIC KEY`)
* PKCS#1 RSAPrivateKey (PEM header: `BEGIN RSA PRIVATE KEY`)


Not only can RSA private keys can be handled by this standard, but also other algorithms. The PKCS8 private keys are typically exchanged through the PEM encoding format.

PEM is a base-64 encoding mechanism of a DER certificate. PEM may also encode other kinds of data such as public/private keys and certificate requests.

```pem
-----BEGIN PUBLIC KEY-----
...Base64 encoding of the DER encoded certificate...
-----END PUBLIC KEY-----
```


## public key  x509
```java
public static RSAPublicKey readPublicKey(File file) throws Exception {
    String key = new String(Files.readAllBytes(file.toPath()), Charset.defaultCharset());

    String publicKeyPEM = key
      .replace("-----BEGIN PUBLIC KEY-----", "")
      .replaceAll(System.lineSeparator(), "")
      .replace("-----END PUBLIC KEY-----", "");

    byte[] encoded = Base64.decodeBase64(publicKeyPEM);

    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encoded);
    return (RSAPublicKey) keyFactory.generatePublic(keySpec);
}
```

## private pkcs8

```java
public RSAPrivateKey readPrivateKey(File file) throws Exception {
    String key = new String(Files.readAllBytes(file.toPath()), Charset.defaultCharset());

    String privateKeyPEM = key
      .replace("-----BEGIN PRIVATE KEY-----", "")
      .replaceAll(System.lineSeparator(), "")
      .replace("-----END PRIVATE KEY-----", "");

    byte[] encoded = Base64.decodeBase64(privateKeyPEM);

    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
    return (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
}
```

## or Using BouncyCastle Library

# Elliptic Curve keys

```bash
openssl ecparam -name secp256k1 -genkey -out privateKey.pem
openssl ec -in privateKey.pem -pubout -out publicKey.pem
```



# Java key and a JWK mapping

https://connect2id.com/products/nimbus-jose-jwt/examples/jwk-conversion


Standard Java key representation	JSON Web Key representation
java.security.Key	com.nimbusds.jose.jwk.JWK
java.security.interfaces.RSAKey
java.security.interfaces.RSAPublicKey
java.security.interfaces.RSAPrivateKey	com.nimbusds.jose.jwk.RSAKey
java.security.interfaces.ECKey
java.security.interfaces.ECPublicKey
java.security.interfaces.ECPrivateKey	com.nimbusds.jose.jwk.ECKey