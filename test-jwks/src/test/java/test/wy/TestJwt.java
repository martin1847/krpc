//package test.wy;
//
//import java.util.Map;
//
//import com.auth0.jwk.Jwk;
//import com.auth0.jwk.JwkProvider;
//import com.auth0.jwk.UrlJwkProvider;
//import com.auth0.jwt.JWT;
//import com.auth0.jwt.algorithms.Algorithm;
//import com.auth0.jwt.interfaces.DecodedJWT;
//import com.bt.rpc.server.jws.Es256Jwk;
//import com.bt.rpc.server.jws.PublicClaims;
//import io.jsonwebtoken.Claims;
//import io.jsonwebtoken.JwtParser;
//import io.jsonwebtoken.Jwts;
//import org.junit.jupiter.api.Test;
//
//
//public class TestJwt {
//
//    //@Inject
//    //JwtCreator jwtCreator;
//
//    //
//    //@BeforeAll
//    //public static void setup() {
//    //    var mock = Mockito.mock(UserMapper.class);
//    //    Mockito.when(mock.getUser(1)).thenReturn(new User(1,"mock 1"));
//    //    QuarkusMock.installMockForType(mock, UserMapper.class);
//    //}
//    //
//
//    @Test
//    public void testJwt() throws Exception {
//
//        //var exp = new Date(System.currentTimeMillis() + HttpConst.ACCESS_TOKEN_IN_COOKIE_EXPIRE_MILLS);
//
//        var token = "eyJraWQiOiJiby10ZXN0LTIxMTEiLCJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIxMjM0IiwiYWRtIjoxLCJleHAiOjE2MzcwNTg3ODR9.1l0vnZfxgJmg1rr2RrQgubQ_RCT3HwymjD92s1qUyYklMrRQr_MDKV8LiY8J7irhN5NEVhAVHQ6G1C2woaQRRA";
//        //jwtCreator.jwt("1234","w-sss",true,exp);
//
//
//        token = "eyJhbGciOiJFUzI1NiIsImtpZCI6ImJvLXRlc3QtMjExMSJ9.eyJzdWIiOiIxMjM0IiwiYWRtIjoxLCJleHAiOjE2MzcxMzc4NDh9._TaUOVjezTIqVcsNA034CK0ogYw6N5lxmsKiqIJsxg0gyq2PpQs1cRMVErq4mmtTs_m8uiZnRBj1pmxGmIoUUw";
//        DecodedJWT jwt = JWT.decode(token);
//        System.out.println("=== " + jwt);
//        JwkProvider provider = new UrlJwkProvider("https://auth.zhulinkeji.com/");//.well-known/jwks.json
//        //JwkProvider provider = new UrlJwkProvider(getClass().getResource("/jwks.json"));
//        Jwk jwk = provider.get(jwt.getKeyId());
//
//        Map valueAttr = jwk.getAdditionalAttributes();
//        valueAttr.put(PublicClaims.KEY_ID,jwt.getKeyId());
//        Es256Jwk myJwk = new Es256Jwk(valueAttr);
//        System.out.println(jwk);
//        var publicKey = myJwk.toECPublicKey();
//        System.out.println("public key " + publicKey );
//
//        //
//        //var bigY = new BigInteger(1,Base64.getUrlDecoder().decode("6s9ECrJurlHCkSx8CTnqhS5HN7h9-dblFgLfpRPcPeg"));
//        //System.out.println("big Y : " + bigY);
//        //
//        //publicKey = loadPublicKey(pubkeyX509);
//
//        Algorithm algorithm = Algorithm.ECDSA256(publicKey,null);
//        algorithm.verify(jwt);
//        JwtParser parser =  Jwts.parserBuilder()
//                .setSigningKey(publicKey)
//                .build();
//        parseJwtAccessToken(parser,token);
//        System.out.println("token is ok :" + token);
//        //
//        //Jwts.builder()
//        //        .setSubject("")
//        //        //.setExpiration(expireAt)
//        //        .signWith(null, SignatureAlgorithm.ES256)
//        //        .compact();
//
//    }
//
//    public static Claims parseJwtAccessToken(JwtParser parser,String accessToken) {
//        if (null == accessToken || accessToken.isEmpty()) {
//            return null;
//        }
//        try {
//            return parser.parseClaimsJws(accessToken).getBody();
//        } catch (Exception  e) {
//            //ExpiredJwtException
//            e.printStackTrace();
//            return null;
//        }
//
//    }
//
//    //private static ECPublicKey loadPublicKey(String base64oneline) {
//    //    byte[] publicKeyBytes = Base64.getDecoder().decode(base64oneline);
//    //    try {
//    //        X509EncodedKeySpec pubX509 = new X509EncodedKeySpec(publicKeyBytes);
//    //        return (ECPublicKey)KeyFactory.getInstance("EC") .generatePublic(pubX509);
//    //    } catch (Exception e) {
//    //        throw new RuntimeException(e);
//    //    }
//    //}
//}