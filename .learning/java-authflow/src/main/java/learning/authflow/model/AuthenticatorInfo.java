package learning.authflow.model;

import lombok.Data;
import java.io.Serializable;

@Data
public class AuthenticatorInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    private String authenticatorId;
    private String authenticatorType;
    private String oobTarget;  // email or phone
}
